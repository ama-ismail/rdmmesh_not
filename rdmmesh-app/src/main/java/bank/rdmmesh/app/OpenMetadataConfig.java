package bank.rdmmesh.app;

import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * Настройки клиента OpenMetadata REST. Используются единственным местом — lazy lookup'ом
 * {@code om_user_id} по {@code preferred_username} при первом appearance JWT (SPEC §2.4).
 *
 * <p>Все поля опциональны: при пустом {@link #baseUrl} {@code IdentityPort} должен корректно
 * деградировать (не блокировать аутентификацию, ставить provisional mapping и логировать
 * предупреждение). Это нужно для dev-стендов, где OM не поднят, и для случая network-glitch
 * во время первого логина пользователя.
 */
public final class OpenMetadataConfig {

    @JsonProperty("baseUrl")
    private String baseUrl = "";

    @JsonProperty("botToken")
    private String botToken = "";

    /** Connection timeout для REST-вызовов в OM. Парсится как Dropwizard duration. */
    @JsonProperty("connectTimeout")
    @NotNull
    private io.dropwizard.util.Duration connectTimeout = io.dropwizard.util.Duration.seconds(2);

    /** Read timeout для REST-вызовов в OM. Парсится как Dropwizard duration. */
    @JsonProperty("requestTimeout")
    @NotNull
    private io.dropwizard.util.Duration requestTimeout = io.dropwizard.util.Duration.seconds(5);

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getBotToken() {
        return botToken;
    }

    public Duration getConnectTimeout() {
        return Duration.ofNanos(connectTimeout.toNanoseconds());
    }

    public Duration getRequestTimeout() {
        return Duration.ofNanos(requestTimeout.toNanoseconds());
    }

    /** True, если OM-клиент должен быть инициализирован (есть и URL, и токен). */
    public boolean isEnabled() {
        return baseUrl != null
                && !baseUrl.isBlank()
                && botToken != null
                && !botToken.isBlank();
    }

    public Optional<String> baseUrlOpt() {
        return Optional.ofNullable(baseUrl).filter(s -> !s.isBlank());
    }
}
