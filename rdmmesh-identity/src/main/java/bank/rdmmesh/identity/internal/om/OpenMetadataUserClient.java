package bank.rdmmesh.identity.internal.om;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Минимальный клиент к OpenMetadata REST для единственной операции — резолва {@code User.id}
 * по {@code preferred_username} (sAMAccountName). Используется один раз на пользователя:
 * после успешного lookup'а результат пишется в {@code identity.rdm_user_mapping} и больше
 * этот класс на пути JWT-валидации не появляется.
 *
 * <p>Если OM не настроен (см. {@link bank.rdmmesh.app.OpenMetadataConfig#isEnabled()}) — этот
 * класс не должен быть инстанциирован вовсе. {@link KeycloakIdentityPort} мягко деградирует
 * до provisional-маппинга на deterministic UUID.
 */
public final class OpenMetadataUserClient {

    private static final Logger log = LoggerFactory.getLogger(OpenMetadataUserClient.class);

    private final URI baseUri;
    private final String botToken;
    private final HttpClient http;
    private final ObjectMapper json;
    private final Duration requestTimeout;

    public OpenMetadataUserClient(
            String baseUrl, String botToken, Duration connectTimeout, Duration requestTimeout) {
        this.baseUri = URI.create(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.botToken = Objects.requireNonNull(botToken, "botToken");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.json = new ObjectMapper();
    }

    /**
     * Возвращает {@code Optional<UUID>} — id пользователя в OM. Empty при HTTP 404, brake — при
     * 5xx / network error. Никогда не бросает {@link InterruptedException} наверх (восстанавливает
     * interrupt-флаг и возвращает пустой Optional с warn).
     */
    public Optional<UUID> findUserIdByName(String username) {
        Objects.requireNonNull(username, "username");
        var requestUri = baseUri.resolve("api/v1/users/name/"
                + URLEncoder.encode(username, StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(requestUri)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + botToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 404) {
                log.debug("OM: user {} not found", username);
                return Optional.empty();
            }
            if (status >= 200 && status < 300) {
                JsonNode node = json.readTree(response.body());
                JsonNode idNode = node.path("id");
                if (idNode.isMissingNode() || idNode.isNull()) {
                    log.warn("OM: response for user {} has no id field", username);
                    return Optional.empty();
                }
                try {
                    return Optional.of(UUID.fromString(idNode.asText()));
                } catch (IllegalArgumentException ex) {
                    log.warn("OM: id for user {} is not UUID: {}", username, idNode.asText());
                    return Optional.empty();
                }
            }
            log.warn("OM: unexpected status {} for user {}", status, username);
            return Optional.empty();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("OM: lookup of {} interrupted", username);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("OM: lookup of {} failed: {}", username, e.toString());
            return Optional.empty();
        }
    }
}
