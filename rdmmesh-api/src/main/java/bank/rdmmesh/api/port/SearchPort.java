package bank.rdmmesh.api.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Full-text search over CodeSet metadata and CodeItem labels. Backed by Postgres FTS +
 * pg_trgm in the MVP; future ES adapter can replace it without changes to callers.
 * SPEC ADR-003.
 */
public interface SearchPort {

    record Hit(UUID id, String type, String displayName, double score) {}

    record Query(
            String text,
            Optional<UUID> domainId,
            Optional<String> language,
            int page,
            int size) {}

    List<Hit> search(Query query);
}
