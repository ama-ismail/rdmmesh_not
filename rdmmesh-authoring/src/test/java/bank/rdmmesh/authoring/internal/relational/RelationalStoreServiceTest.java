package bank.rdmmesh.authoring.internal.relational;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import bank.rdmmesh.api.eventbus.VersionPublishedDomainEvent;
import bank.rdmmesh.spec.events.VersionPublishedEvent;

/**
 * Pure-тесты best-effort гарантий publish-хука {@link RelationalStoreService}
 * (Stage 2-final). Без БД: проверяем, что {@code onVersionPublished} никогда не
 * бросает — иначе сбой реляционного зеркала ронял бы шину/publishing.
 */
class RelationalStoreServiceTest {

    /** jdbi/catalog null — допустимо: все ветки ниже не должны доходить до их использования
     *  без перехвата. */
    private final RelationalStoreService store =
            new RelationalStoreService(null, null, new ObjectMapper());

    @Test
    void onVersionPublished_null_payload_is_noop() {
        VersionPublishedDomainEvent event =
                new VersionPublishedDomainEvent(UUID.randomUUID(), OffsetDateTime.now(), null);
        assertThatCode(() -> store.onVersionPublished(event)).doesNotThrowAnyException();
    }

    @Test
    void onVersionPublished_blank_version_id_is_noop() {
        VersionPublishedDomainEvent event = new VersionPublishedDomainEvent(
                UUID.randomUUID(), OffsetDateTime.now(), new VersionPublishedEvent());
        assertThatCode(() -> store.onVersionPublished(event)).doesNotThrowAnyException();
    }

    @Test
    void onVersionPublished_bad_version_id_is_swallowed() {
        VersionPublishedEvent payload = new VersionPublishedEvent().withVersionId("not-a-uuid");
        VersionPublishedDomainEvent event =
                new VersionPublishedDomainEvent(UUID.randomUUID(), OffsetDateTime.now(), payload);
        assertThatCode(() -> store.onVersionPublished(event)).doesNotThrowAnyException();
    }

    @Test
    void onVersionPublished_backend_error_is_swallowed() {
        // Валидный version_id, но jdbi == null → внутренний сбой должен быть поглощён
        // (best-effort), а не выброшен наружу в шину.
        VersionPublishedEvent payload =
                new VersionPublishedEvent().withVersionId(UUID.randomUUID().toString());
        VersionPublishedDomainEvent event =
                new VersionPublishedDomainEvent(UUID.randomUUID(), OffsetDateTime.now(), payload);
        assertThatCode(() -> store.onVersionPublished(event)).doesNotThrowAnyException();
    }
}
