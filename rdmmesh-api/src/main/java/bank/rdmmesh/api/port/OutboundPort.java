package bank.rdmmesh.api.port;

import bank.rdmmesh.spec.events.VersionPublishedEvent;

/**
 * Delivery of business events to external consumer systems. MVP adapter is a webhook
 * dispatcher backed by the {@code publishing.webhook_outbox} transactional outbox.
 * V2 may swap in a Kafka adapter behind the same port (SPEC ADR-004 + §5.2 V2).
 */
public interface OutboundPort {

    /**
     * Persist the event into the outbox in the SAME transaction as the publish. The
     * background worker drains the outbox with at-least-once + idempotent semantics.
     */
    void enqueueVersionPublished(VersionPublishedEvent event);
}
