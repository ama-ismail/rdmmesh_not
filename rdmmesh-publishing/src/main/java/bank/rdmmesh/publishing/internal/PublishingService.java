package bank.rdmmesh.publishing.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;

import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.eventbus.VersionPublishedDomainEvent;
import bank.rdmmesh.api.eventbus.WorkflowTransitionDomainEvent;
import bank.rdmmesh.api.port.CatalogReadPort;
import bank.rdmmesh.api.port.CatalogReadPort.CodeSetSnapshot;
import bank.rdmmesh.api.port.CatalogReadPort.DomainSnapshot;
import bank.rdmmesh.api.port.OutboundPort;
import bank.rdmmesh.api.port.PublishedSnapshotPort;
import bank.rdmmesh.api.port.VersionLifecyclePort;
import bank.rdmmesh.api.port.VersionLifecyclePort.PublishedVersionDetails;
import bank.rdmmesh.api.port.VersionLifecyclePort.VersionSnapshot;
import bank.rdmmesh.api.port.WorkflowJournalPort;
import bank.rdmmesh.spec.events.VersionPublishedEvent;

/**
 * Эпик E6 (Publishing) — авто-publish после OWNER_APPROVED.
 *
 * <p>Подписывается на {@link WorkflowTransitionDomainEvent} с фильтром
 * {@code payload.to == OWNER_APPROVED} и:
 * <ol>
 *   <li>Читает canonical bytes снапшота через {@link PublishedSnapshotPort};</li>
 *   <li>Считает {@code content_hash = sha256_hex(bytes)};</li>
 *   <li>Считает {@code approval_signature = hmac_sha256_hex(content_hash + approver + ts, key)};</li>
 *   <li>Атомарно публикует версию (CAS OWNER_APPROVED → PUBLISHED + content_hash + signature + published_by/at);</li>
 *   <li>Если у CodeSet'а была предыдущая PUBLISHED — переводит её в DEPRECATED;</li>
 *   <li>Логирует оба system-перехода в {@code workflow.workflow_transition}.</li>
 * </ol>
 *
 * <p><b>Изоляция транзакций</b> — на пилоте OK как best-effort: lifecycle.publish и
 * journal.recordSystemTransition идут в разных транзакциях, как и в E5 для
 * regular transitions. Полный 2PC откладывается до E10 (см. handoff E5 §1.4).
 *
 * <p><b>Идемпотентность.</b> Если событие пришло повторно (audit replay, ручной
 * trigger), {@link VersionLifecyclePort#publish} вернёт false (статус уже не
 * OWNER_APPROVED) — обработка корректно отвалится без побочных эффектов.
 */
public final class PublishingService {

    private static final Logger log = LoggerFactory.getLogger(PublishingService.class);

    /** SYSTEM-актор для лога system-переходов. */
    public static final UUID SYSTEM_ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000000");

    /** Версия event-схемы {@code VersionPublishedEvent} (см. spec/events/...). */
    private static final int EVENT_SCHEMA_VERSION = 1;

    private final VersionLifecyclePort lifecycle;
    private final PublishedSnapshotPort snapshots;
    private final CatalogReadPort catalog;
    private final WorkflowJournalPort journal;
    private final HmacSigner signer;
    private final OutboundPort outbound;
    private final EventBus eventBus;

    public PublishingService(
            VersionLifecyclePort lifecycle,
            PublishedSnapshotPort snapshots,
            CatalogReadPort catalog,
            WorkflowJournalPort journal,
            HmacSigner signer,
            OutboundPort outbound,
            EventBus eventBus) {
        this.lifecycle = lifecycle;
        this.snapshots = snapshots;
        this.catalog = catalog;
        this.journal = journal;
        this.signer = signer;
        this.outbound = outbound;
        this.eventBus = eventBus;
    }

    /** Подписать сервис на события о приходе OWNER_APPROVED. */
    public void registerOn(EventBus bus) {
        bus.subscribe(WorkflowTransitionDomainEvent.class, this::onTransition);
    }

    void onTransition(WorkflowTransitionDomainEvent event) {
        if (event.payload() == null || event.payload().getTo() == null) return;
        String to = event.payload().getTo().value();
        if (!"OWNER_APPROVED".equals(to)) return;

        UUID versionId = UUID.fromString(event.payload().getVersionId());
        UUID approver  = UUID.fromString(event.payload().getActor());
        autoPublish(versionId, approver);
    }

    /** Доступно для unit/IT-тестов и для возможного manual re-trigger в админ-консоли (V1+). */
    public PublishOutcome autoPublish(UUID versionId, UUID approverOmUserId) {
        VersionSnapshot version = lifecycle.findVersion(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown version: " + versionId));
        if (!"OWNER_APPROVED".equals(version.status())) {
            log.warn("publishing: version {} в статусе {} — пропускаю auto-publish", versionId, version.status());
            return PublishOutcome.SKIPPED;
        }

        CodeSetSnapshot cs = catalog.findCodeSet(version.codesetId())
                .orElseThrow(() -> new IllegalStateException(
                        "CodeSet missing for version " + versionId));

        // 1) hash + signature
        byte[] canonical = snapshots.canonicalSnapshotBytes(versionId);
        String contentHash = HmacSigner.sha256Hex(canonical);
        String iso = Instant.now().toString();
        String signature = signer.signApproval(contentHash, approverOmUserId.toString(), iso);

        // 2) Запоминаем предыдущую PUBLISHED ДО самого publish'а, иначе после CAS
        // findLatestPublished вернёт уже эту же versionId (она самая свежая по
        // published_at) и auto-deprecate не сработает.
        Optional<VersionSnapshot> prev = lifecycle.findLatestPublished(version.codesetId());

        // 3) CAS OWNER_APPROVED → PUBLISHED + crypto fields
        boolean ok = lifecycle.publish(versionId, contentHash, signature, approverOmUserId);
        if (!ok) {
            log.warn("publishing: CAS publish для {} вернул false (concurrent transition?)", versionId);
            return PublishOutcome.SKIPPED;
        }

        // 4) журнал publish
        journal.recordSystemTransition(
                versionId, version.codesetId(), cs.domainId(),
                "OWNER_APPROVED", "PUBLISHED", "publish",
                SYSTEM_ACTOR, "auto-publish after owner_approve");

        // 5) auto-DEPRECATE предыдущей PUBLISHED (если есть и не та же самая)
        prev.ifPresent(p -> {
            if (!p.id().equals(versionId)) {
                boolean dep = lifecycle.deprecate(p.id());
                if (dep) {
                    journal.recordSystemTransition(
                            p.id(), version.codesetId(), cs.domainId(),
                            "PUBLISHED", "DEPRECATED", "deprecate",
                            SYSTEM_ACTOR, "auto-deprecate, superseded by " + versionId);
                }
            }
        });

        log.info("publishing: PUBLISHED version_id={} codeset_id={} content_hash={} approver={}",
                versionId, version.codesetId(), contentHash, approverOmUserId);

        // 6) Outbound (E9): enqueue в webhook_outbox для consumer-систем. Отдельная
        // транзакция от publish'а — best-effort, как и journal'ный split в шаге (4).
        // При сбое здесь версия уже PUBLISHED и доступна через REST distribution;
        // потеря именно push-уведомления компенсируется poll-mode'ом consumer'ов.
        VersionPublishedEvent event = null;
        try {
            event = buildPublishedEvent(versionId, version.codesetId(), cs, prev.orElse(null));
            outbound.enqueueVersionPublished(event);
        } catch (RuntimeException e) {
            log.warn("publishing: enqueueVersionPublished failed for {}: {}",
                    versionId, e.toString(), e);
        }

        // 7) Audit (E10): публикуем VersionPublishedDomainEvent в in-process bus.
        // Глобальный subscriber rdmmesh-audit получит событие и запишет в audit_log.
        // Если payload не построился (шаг 6 свалился до сборки) — отправляем
        // минимальный event только с обязательными полями, чтобы audit не молчал.
        try {
            VersionPublishedEvent forBus = event != null
                    ? event
                    : minimalPublishedEvent(versionId, version.codesetId(), cs, approverOmUserId, contentHash, signature);
            eventBus.publish(new VersionPublishedDomainEvent(
                    UUID.fromString(forBus.getEventId()),
                    OffsetDateTime.now(),
                    forBus));
        } catch (RuntimeException e) {
            log.warn("publishing: eventBus.publish(VersionPublishedDomainEvent) failed for {}: {}",
                    versionId, e.toString());
        }

        return PublishOutcome.PUBLISHED;
    }

    /**
     * Минимальный payload, если основной {@link #buildPublishedEvent} не собрался
     * (например, упало чтение PublishedDetails). Содержит только обязательные поля
     * spec-схемы (event_id, schema_version, *_id, version, hash, signature, published_at)
     * — этого достаточно для audit-записи, но недостаточно для outbound payload'а.
     */
    private VersionPublishedEvent minimalPublishedEvent(
            UUID versionId, UUID codesetId, CodeSetSnapshot cs,
            UUID approver, String contentHash, String signature) {
        return new VersionPublishedEvent()
                .withEventId(UUID.randomUUID().toString())
                .withSchemaVersion(EVENT_SCHEMA_VERSION)
                .withDomainId(cs.domainId().toString())
                .withCodesetId(codesetId.toString())
                .withVersionId(versionId.toString())
                .withVersion("?")
                .withContentHash(contentHash)
                .withApprovalSignature(signature)
                .withPublishedAt(Instant.now().toString())
                .withPublishedBy(approver.toString());
    }

    private VersionPublishedEvent buildPublishedEvent(
            UUID versionId, UUID codesetId, CodeSetSnapshot cs, VersionSnapshot prev) {
        PublishedVersionDetails details = lifecycle.findPublishedDetails(versionId)
                .orElseThrow(() -> new IllegalStateException(
                        "версия " + versionId + " не в PUBLISHED после publish CAS"));
        DomainSnapshot domain = catalog.findDomain(cs.domainId()).orElse(null);
        VersionPublishedEvent ev = new VersionPublishedEvent()
                .withEventId(UUID.randomUUID().toString())
                .withSchemaVersion(EVENT_SCHEMA_VERSION)
                .withDomainId(cs.domainId().toString())
                .withCodesetId(codesetId.toString())
                .withVersionId(versionId.toString())
                .withVersion(details.version())
                .withContentHash(details.contentHash())
                .withApprovalSignature(details.approvalSignature())
                .withPublishedAt(details.publishedAt() == null
                        ? Instant.now().toString()
                        : details.publishedAt().toString());
        if (domain != null) ev.setDomainName(domain.name());
        ev.setCodesetName(cs.name());
        if (details.publishedBy() != null) ev.setPublishedBy(details.publishedBy().toString());
        if (details.itemCount() != null) ev.setItemCount(details.itemCount());
        if (prev != null && !prev.id().equals(versionId)) {
            ev.setPreviousVersion(prev.version());
        }
        return ev;
    }

    /**
     * Проверка целостности published-версии — для GET /versions/&#123;id&#125;/verify.
     * Пересчитывает content_hash из текущих items и сверяет с хранимым.
     */
    public VerifyResult verify(UUID versionId) {
        VersionSnapshot version = lifecycle.findVersion(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown version: " + versionId));
        if (!"PUBLISHED".equals(version.status()) && !"DEPRECATED".equals(version.status())) {
            return new VerifyResult(false, false, null, null,
                    "Версия в статусе " + version.status() + " — verify применим только к PUBLISHED/DEPRECATED");
        }
        byte[] canonical = snapshots.canonicalSnapshotBytes(versionId);
        String computed = HmacSigner.sha256Hex(canonical);
        String stored = lifecycle.findStoredContentHash(versionId).orElse(null);
        boolean verified = stored != null && stored.equalsIgnoreCase(computed);
        return new VerifyResult(true, verified, computed, stored, null);
    }

    public enum PublishOutcome { PUBLISHED, SKIPPED }

    public record VerifyResult(
            boolean applicable,
            boolean verified,
            String computedHash,
            String storedHash,
            String note) {}
}
