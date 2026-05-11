package bank.rdmmesh.api.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Узкий write-side контракт для управления статусом {@code authoring.code_set_version}.
 * SPEC §3.3 закрепляет: schemas {@code catalog} / {@code authoring} пишет только модуль
 * authoring. Workflow при transition'ах не лезет в эти схемы напрямую — он вызывает
 * этот порт, реализованный в authoring.
 *
 * <p>Всё, что не connectable со статусной машиной (создание draft'а, редактирование
 * items'ов и т.п.) — остаётся внутренним API authoring и наружу не торчит.
 */
public interface VersionLifecyclePort {

    Optional<VersionSnapshot> findVersion(UUID versionId);

    /** Список om_user_id steward'ов, чьи steward_approve уже зафиксированы. */
    Set<UUID> reviewersOf(UUID versionId);

    /** Проверка at-most-one: есть ли у CodeSet'а уже открытая (non-terminal) версия. */
    List<VersionSnapshot> openVersionsFor(UUID codesetId);

    /**
     * Атомарный CAS-переход статуса. В одной транзакции:
     * <ol>
     *   <li>UPDATE {@code code_set_version} SET status = :to WHERE id = :id AND status = :from
     *       — на 0 affected rows возвращается false, ничего больше не делается.</li>
     *   <li>Side-effects на колонках, зависят от целевого статуса (см. {@link TransitionEffect}):
     *       при STEWARD_APPROVED — INSERT в {@code code_set_version_reviewer (actor)};
     *       при OWNER_APPROVED — SET approved_by = :actor.</li>
     * </ol>
     *
     * @return true если статус совпал и переход применён; false если строка
     *         в ином статусе (то есть concurrent transition или version404).
     */
    boolean transition(
            UUID versionId,
            String fromStatus,
            String toStatus,
            UUID actor,
            TransitionEffect effect);

    /**
     * Атомарно опубликовать версию (CAS OWNER_APPROVED → PUBLISHED) с содержимым подписи.
     * Вызывает PublishingService после успешного {@code OwnerApproved} транзита.
     *
     * @return true если CAS прошёл (статус был OWNER_APPROVED), false если concurrent
     *         transition либо неверный versionId.
     */
    boolean publish(UUID versionId, String contentHash, String signature, UUID publishedBy);

    /** Атомарно перевести PUBLISHED → DEPRECATED (для autodeprecate предыдущей PUBLISHED). */
    boolean deprecate(UUID versionId);

    /** Текущая published-версия codeset'а (на момент вызова), если есть. */
    Optional<VersionSnapshot> findLatestPublished(UUID codesetId);

    /** Сохранённый {@code content_hash} версии — для verify-endpoint (E6). */
    Optional<String> findStoredContentHash(UUID versionId);

    /**
     * Богатый snapshot опубликованной версии — для построения {@code VersionPublishedEvent}
     * payload'а outbound webhook'а (E9). Возвращает {@code Optional.empty()}, если версия
     * не в {@code PUBLISHED}/{@code DEPRECATED} (то есть crypto fields ещё не заполнены).
     */
    Optional<PublishedVersionDetails> findPublishedDetails(UUID versionId);

    /**
     * Side-effects в одной транзакции с CAS статуса. Workflow указывает явно,
     * чтобы порт не выводил эффекты из (from,to) и не наследовал бизнес-логику.
     */
    record TransitionEffect(boolean recordReviewer, boolean setApprover) {
        public static TransitionEffect none() {
            return new TransitionEffect(false, false);
        }
        public static TransitionEffect reviewer() {
            return new TransitionEffect(true, false);
        }
        public static TransitionEffect approver() {
            return new TransitionEffect(false, true);
        }
    }

    record VersionSnapshot(
            UUID id,
            UUID codesetId,
            String version,
            String status,
            UUID createdBy) {}

    /** Снимок published-версии со всеми crypto + audit-полями. */
    record PublishedVersionDetails(
            UUID id,
            UUID codesetId,
            String version,
            String status,
            String contentHash,
            String approvalSignature,
            UUID publishedBy,
            Instant publishedAt,
            Integer itemCount) {}
}
