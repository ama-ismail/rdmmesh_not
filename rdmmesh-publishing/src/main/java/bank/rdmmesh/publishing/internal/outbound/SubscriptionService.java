package bank.rdmmesh.publishing.internal.outbound;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;

import bank.rdmmesh.publishing.internal.outbound.dao.SubscriptionDao;
import bank.rdmmesh.publishing.internal.outbound.dao.SubscriptionDao.SubscriptionRow;

/**
 * Бизнес-операции над {@code publishing.webhook_subscription}: CRUD + simple validation.
 * Логика безопасности (RBAC) находится в resource'е через {@code @RolesAllowed}.
 */
public final class SubscriptionService {

    private final Jdbi jdbi;
    private final ObjectMapper json;

    public SubscriptionService(Jdbi jdbi, ObjectMapper json) {
        this.jdbi = jdbi;
        this.json = json;
    }

    public List<SubscriptionRow> list() {
        return jdbi.withExtension(SubscriptionDao.class, SubscriptionDao::findAll);
    }

    public Optional<SubscriptionRow> findById(UUID id) {
        return jdbi.withExtension(SubscriptionDao.class, dao -> dao.findById(id));
    }

    /**
     * @throws IllegalArgumentException если url/secret_id невалидны или filter — не объект.
     */
    public SubscriptionRow create(
            String url, String secretId, Map<String, Object> filter, boolean active, UUID createdBy) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        // SPEC §3.5: url — корректный URI; полный валидатор RFC 3986 не нужен,
        // достаточно проверки JDK URI parser'ом + http(s) схемы.
        try {
            var u = java.net.URI.create(url);
            String scheme = u.getScheme();
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
                throw new IllegalArgumentException("url scheme must be http or https: " + url);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("url is not a valid URI: " + url, e);
        }
        if (secretId == null || secretId.isBlank()) {
            throw new IllegalArgumentException("secret_id is required");
        }
        // {} — пустой no-op-фильтр; не путать с null. Мы храним именно пустой объект.
        Map<String, Object> safeFilter = filter == null ? Map.of() : filter;
        String filterJson;
        try {
            filterJson = json.writeValueAsString(safeFilter);
        } catch (Exception e) {
            throw new IllegalArgumentException("filter is not serializable as JSON: " + e.getMessage(), e);
        }

        UUID id = UUID.randomUUID();
        jdbi.useExtension(SubscriptionDao.class,
                dao -> dao.insert(id, url, secretId, filterJson, active, createdBy));
        return findById(id).orElseThrow(() ->
                new IllegalStateException("subscription " + id + " not visible after insert"));
    }

    /** Soft-deactivation: SPEC §5.1 говорит DELETE; реальная семантика — деактивация. */
    public boolean deactivate(UUID id) {
        return jdbi.withExtension(SubscriptionDao.class, dao -> dao.deactivate(id)) > 0;
    }
}
