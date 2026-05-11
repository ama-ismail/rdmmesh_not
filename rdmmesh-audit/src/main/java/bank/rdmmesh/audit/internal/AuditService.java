package bank.rdmmesh.audit.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.eventbus.DomainEvent;
import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.eventbus.OwnershipChangedDomainEvent;
import bank.rdmmesh.api.eventbus.VersionPublishedDomainEvent;
import bank.rdmmesh.api.eventbus.WorkflowTransitionDomainEvent;
import bank.rdmmesh.audit.internal.dao.AuditLogDao;

/**
 * Эпик E10 (Audit). Подписывается на {@link DomainEvent} глобально и пишет каждое
 * событие в {@code audit.audit_log} (append-only, INSERT-only grants + триггеры
 * против UPDATE/DELETE/TRUNCATE на уровне Postgres).
 *
 * <p>Сериализация payload'а: для известных типов берём оригинальный spec-POJO
 * (REST-/webhook-шейп), для неизвестных — generic JSON-представление через Jackson.
 * Цель — иметь достаточно контекста, чтобы при инциденте можно было реконструировать,
 * какое именно состояние было записано в систему в этот момент.
 *
 * <p>Idempotency: INSERT использует {@code ON CONFLICT (event_id, event_type) DO NOTHING}
 * (UNIQUE-индекс audit_log_event_id_uq, миграция V071). Replay одного и того же
 * события не создаёт дубликата.
 *
 * <p>Транзакции: subscriber выполняется синхронно в потоке publisher'а, но в
 * собственной транзакции через {@link Jdbi#useHandle}. Сбой INSERT'а в audit
 * НЕ откатывает работу publisher'а — {@link bank.rdmmesh.api.eventbus.EventBus}
 * ловит исключения подписчиков и логирует их (см. {@code SyncEventBus}). Это
 * сознательная компромиссная позиция пилота: best-effort audit предпочтительнее
 * блокировки бизнес-операции при отказе аудит-БД.
 */
public final class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final Jdbi jdbi;
    private final ObjectMapper json;

    public AuditService(Jdbi jdbi, ObjectMapper json) {
        this.jdbi = jdbi;
        this.json = json;
    }

    public void registerOn(EventBus bus) {
        bus.subscribe(DomainEvent.class, this::onEvent);
    }

    void onEvent(DomainEvent event) {
        AuditEventClassifier.Classification c = AuditEventClassifier.classify(event);
        final String payload = serialise(event, c);
        try {
            int rows = jdbi.withExtension(AuditLogDao.class, dao -> dao.insert(
                    event.eventId(),
                    c.eventType(),
                    c.aggregateType(),
                    c.aggregateId(),
                    c.actor(),
                    event.occurredAt(),
                    payload));
            if (rows == 0) {
                log.debug("audit: duplicate event_id={} event_type={} — пропущено по UNIQUE",
                        event.eventId(), c.eventType());
            }
        } catch (RuntimeException e) {
            log.warn("audit: INSERT failed for {} ({}): {}",
                    c.eventType(), event.eventId(), e.toString());
        }
    }

    private String serialise(DomainEvent event, AuditEventClassifier.Classification c) {
        try {
            return json.writeValueAsString(extractPayload(event));
        } catch (JsonProcessingException e) {
            log.warn("audit: payload serialisation failed for {} ({}): {}",
                    c.eventType(), event.eventId(), e.toString());
            return "{}";
        }
    }

    private static Object extractPayload(DomainEvent event) {
        // Сериализуем оригинальный spec-POJO напрямую — он же является body
        // outbound webhook'а / REST-event'а. Audit держит ровно тот контекст,
        // который видели потребители событий.
        if (event instanceof WorkflowTransitionDomainEvent w)  return w.payload();
        if (event instanceof VersionPublishedDomainEvent v)    return v.payload();
        if (event instanceof OwnershipChangedDomainEvent o)    return o.payload();
        return event;
    }
}
