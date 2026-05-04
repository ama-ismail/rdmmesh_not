package bank.rdmmesh.api.eventbus;

import java.util.function.Consumer;

/**
 * Synchronous in-process publish/subscribe. The audit module subscribes here; nothing
 * else *needs* to. Delivery happens within the publisher's transaction — subscribers
 * that want async semantics push their own work onto an outbox.
 */
public interface EventBus {

    <E extends DomainEvent> void publish(E event);

    <E extends DomainEvent> Subscription subscribe(Class<E> type, Consumer<E> handler);

    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
