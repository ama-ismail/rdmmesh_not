package bank.rdmmesh.api.eventbus;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Marker for everything published on the in-process event bus. */
public interface DomainEvent {
    UUID eventId();

    OffsetDateTime occurredAt();
}
