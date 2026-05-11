package bank.rdmmesh.workflow.internal.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.eventbus.WorkflowTransitionDomainEvent;
import bank.rdmmesh.api.port.CatalogReadPort;
import bank.rdmmesh.api.port.CatalogReadPort.CodeSetSnapshot;
import bank.rdmmesh.api.port.OwnershipPort;
import bank.rdmmesh.api.port.VersionLifecyclePort;
import bank.rdmmesh.api.port.VersionLifecyclePort.TransitionEffect;
import bank.rdmmesh.api.port.VersionLifecyclePort.VersionSnapshot;
import bank.rdmmesh.api.port.WorkflowPort.IllegalStateTransitionException;
import bank.rdmmesh.spec.api.TransitionRequest;
import bank.rdmmesh.spec.entity.AssetOwnership;
import bank.rdmmesh.spec.events.WorkflowTransitionEvent;
import bank.rdmmesh.workflow.internal.StateMachine;
import bank.rdmmesh.workflow.internal.StateMachine.Action;
import bank.rdmmesh.workflow.internal.StateMachine.Decision;
import bank.rdmmesh.workflow.internal.StateMachine.Status;
import bank.rdmmesh.workflow.internal.dao.ApprovalTaskDao;
import bank.rdmmesh.workflow.internal.dao.ApprovalTaskDao.ApprovalTaskRow;
import bank.rdmmesh.workflow.internal.dao.WorkflowTransitionDao;
import bank.rdmmesh.workflow.internal.dao.WorkflowTransitionDao.TransitionRow;

/**
 * Оркестратор 4-eyes workflow. Связывает чистую {@link StateMachine} с side-DB-операциями:
 * <ol>
 *   <li>загрузка контекста (current status, created_by, reviewers, assetRoles, domainId);</li>
 *   <li>{@link StateMachine#validate} — все проверки в pure-логике;</li>
 *   <li>в одной транзакции authoring'а: CAS статуса + reviewer/approver side-effect;</li>
 *   <li>append-only INSERT в {@code workflow.workflow_transition} + upsert/close
 *       {@code workflow.approval_task};</li>
 *   <li>publish {@link WorkflowTransitionEvent} в in-process bus (audit/publishing
 *       подписываются в E10/E6).</li>
 * </ol>
 *
 * <p><b>Изоляция транзакций.</b> CAS статуса делается через {@link VersionLifecyclePort}
 * в схеме {@code authoring}, а INSERT в transition log — в схеме {@code workflow}. Это
 * две разные транзакции. Если CAS прошёл, а вторая транзакция упала — у нас несинхронизованные
 * статус и журнал. Для пилота это приемлемо (журнал самозалечивается на следующих
 * transition'ах: история восстанавливается из {@code created_at} и текущего статуса).
 * Полный 2PC / сага — будущая работа, упомянем в handoff'е.
 */
public final class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final Jdbi jdbi;
    private final VersionLifecyclePort lifecycle;
    private final OwnershipPort ownership;
    private final CatalogReadPort catalog;
    private final EventBus eventBus;

    public WorkflowService(
            Jdbi jdbi,
            VersionLifecyclePort lifecycle,
            OwnershipPort ownership,
            CatalogReadPort catalog,
            EventBus eventBus) {
        this.jdbi = jdbi;
        this.lifecycle = lifecycle;
        this.ownership = ownership;
        this.catalog = catalog;
        this.eventBus = eventBus;
    }

    public WorkflowTransitionEvent transition(
            UUID versionId,
            String targetStatus,
            UUID actor,
            Set<String> baseRoles,
            String comment) {

        VersionSnapshot version = lifecycle.findVersion(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown version: " + versionId));

        Status from = parseStatus(version.status());
        Status to   = parseStatus(targetStatus);

        CodeSetSnapshot codeSet = catalog.findCodeSet(version.codesetId())
                .orElseThrow(() -> new IllegalStateException(
                        "CodeSet missing for version " + versionId + " (codeset=" + version.codesetId() + ")"));

        Set<String> assetRoles = ownership.rolesOf(version.codesetId(), actor);
        Set<UUID> reviewers = lifecycle.reviewersOf(versionId);

        StateMachine.Request req = new StateMachine.Request(
                from, to, actor, version.createdBy(),
                reviewers, assetRoles, baseRoles, comment);
        Decision decision = StateMachine.validate(req);

        // 1) CAS статуса + reviewer/approver side-effect (одна транзакция authoring'а).
        TransitionEffect effect = new TransitionEffect(
                decision.recordReviewer(), decision.setApprover());
        boolean ok = lifecycle.transition(
                versionId, version.status(), targetStatus, actor, effect);
        if (!ok) {
            // Concurrent transition: пока мы валидировали, кто-то перевёл версию в другой статус.
            throw new IllegalStateTransitionException(
                    "Конкурентный transition: версия " + versionId
                            + " больше не в статусе " + version.status());
        }

        // 2) Журнал и approval-task — отдельная транзакция в workflow-схеме.
        UUID transitionId = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        jdbi.useTransaction(handle -> {
            handle.attach(WorkflowTransitionDao.class).insert(
                    transitionId,
                    versionId,
                    version.codesetId(),
                    codeSet.domainId(),
                    version.status(),
                    targetStatus,
                    decision.action().name(),
                    actor,
                    comment);

            ApprovalTaskDao tasks = handle.attach(ApprovalTaskDao.class);
            // Закрыть текущую задачу для актора (он же её только что выполнил).
            tasks.closeAll(versionId, actor);

            // Открыть следующую задачу для соответствующей роли — если есть.
            String nextRole = StateMachine.nextRequiredRole(to);
            if (nextRole != null) {
                UUID[] candidates = candidatesFor(version.codesetId(), nextRole);
                tasks.upsertOpen(versionId, version.codesetId(), codeSet.domainId(),
                        nextRole, candidates);
            }
        });

        log.info("workflow: {} → {} version_id={} action={} actor={}",
                version.status(), targetStatus, versionId, decision.action(), actor);

        WorkflowTransitionEvent event = buildEvent(
                transitionId, versionId, version.codesetId(), codeSet.domainId(),
                version.status(), targetStatus, decision.action(),
                actor, comment, occurredAt);
        try {
            eventBus.publish(new WorkflowTransitionDomainEvent(
                    transitionId,
                    OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC),
                    event));
        } catch (RuntimeException e) {
            // Подписчики не должны валить транзицию — журнал и статус уже зафиксированы.
            log.warn("workflow: event publish failed (transition_id={}): {}",
                    transitionId, e.toString());
        }
        return event;
    }

    public List<WorkflowTransitionEvent> history(UUID versionId) {
        // Проверим что версия есть — иначе history впустую вернёт пустой список и проглотит 404.
        VersionSnapshot version = lifecycle.findVersion(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown version: " + versionId));
        List<TransitionRow> rows = jdbi.withExtension(WorkflowTransitionDao.class,
                dao -> dao.findByVersion(versionId));
        return rows.stream().map(WorkflowService::toEvent).toList();
    }

    public List<ApprovalTaskRow> openTasksFor(UUID omUserId) {
        return jdbi.withExtension(ApprovalTaskDao.class,
                dao -> dao.findOpenByUser(omUserId));
    }

    public Optional<ApprovalTaskRow> openTaskFor(UUID versionId, String requiredRole) {
        return jdbi.withExtension(ApprovalTaskDao.class,
                dao -> dao.findOne(versionId, requiredRole))
                .filter(r -> r.closedAt() == null);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private UUID[] candidatesFor(UUID codesetId, String requiredRole) {
        List<AssetOwnership> all = ownership.ownersOf(codesetId, "CODESET");
        return all.stream()
                .filter(a -> a.getRole() != null && requiredRole.equals(a.getRole().value()))
                .filter(a -> a.getOmUserId() != null)
                .map(a -> UUID.fromString(a.getOmUserId()))
                .distinct()
                .toArray(UUID[]::new);
    }

    private static WorkflowTransitionEvent buildEvent(
            UUID id,
            UUID versionId,
            UUID codesetId,
            UUID domainId,
            String fromStatus,
            String toStatus,
            Action action,
            UUID actor,
            String comment,
            Instant occurredAt) {
        // Setters сгенерированного POJO принимают String — javaType из JSON Schema
        // jsonschema2pojo тут игнорирует (см. AuthoringMappers / CatalogMappers,
        // тот же паттерн).
        WorkflowTransitionEvent e = new WorkflowTransitionEvent();
        e.setEventId(id.toString());
        e.setVersionId(versionId.toString());
        e.setCodesetId(codesetId == null ? null : codesetId.toString());
        e.setDomainId(domainId == null ? null : domainId.toString());
        e.setFrom(TransitionRequest.VersionStatus.fromValue(fromStatus));
        e.setTo(TransitionRequest.VersionStatus.fromValue(toStatus));
        e.setAction(WorkflowTransitionEvent.TransitionAction.fromValue(action.name()));
        e.setActor(actor.toString());
        e.setComment(comment);
        e.setOccurredAt(OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC).toString());
        return e;
    }

    private static WorkflowTransitionEvent toEvent(TransitionRow row) {
        WorkflowTransitionEvent e = new WorkflowTransitionEvent();
        e.setEventId(row.id().toString());
        e.setVersionId(row.versionId().toString());
        e.setCodesetId(row.codesetId() == null ? null : row.codesetId().toString());
        e.setDomainId(row.domainId() == null ? null : row.domainId().toString());
        e.setFrom(row.fromStatus() == null ? null
                : TransitionRequest.VersionStatus.fromValue(row.fromStatus()));
        e.setTo(TransitionRequest.VersionStatus.fromValue(row.toStatus()));
        e.setAction(WorkflowTransitionEvent.TransitionAction.fromValue(row.action()));
        e.setActor(row.actor().toString());
        e.setComment(row.comment());
        e.setOccurredAt(OffsetDateTime.ofInstant(row.occurredAt(), ZoneOffset.UTC).toString());
        return e;
    }

    private static Status parseStatus(String s) {
        if (s == null) {
            throw new IllegalStateTransitionException("Статус не задан");
        }
        try {
            return Status.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateTransitionException("Неизвестный статус: " + s);
        }
    }
}
