package bank.rdmmesh.identity;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.List;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.port.IdentityPort;
import bank.rdmmesh.identity.internal.KeycloakIdentityPort;
import bank.rdmmesh.identity.internal.jwt.JwksKeyResolver;
import bank.rdmmesh.identity.internal.jwt.JwtValidator;
import bank.rdmmesh.identity.internal.om.OpenMetadataUserClient;

/**
 * Композиционный фасад модуля {@code rdmmesh-identity}. Используется единственным местом —
 * {@code RdmmeshApplication.run()} — для wiring'а {@link IdentityPort} в общий контекст
 * приложения.
 *
 * <p>Пример:
 *
 * <pre>{@code
 * IdentityPort identityPort = IdentityModule.builder()
 *         .jdbi(jdbi)
 *         .keycloak(config.getKeycloak())
 *         .openmetadata(config.getOpenmetadata())
 *         .build();
 * }</pre>
 *
 * <p>Решение: вместо своего DI-контейнера — статический builder. Композиция явная, легко
 * тестируется и не тащит фреймворк-зависимостей в bounded context.
 */
public final class IdentityModule {

    private static final Logger log = LoggerFactory.getLogger(IdentityModule.class);

    private IdentityModule() {}

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Jdbi jdbi;
        private KeycloakSettings keycloak;
        private OpenMetadataSettings om;

        public Builder jdbi(Jdbi jdbi) {
            this.jdbi = jdbi;
            return this;
        }

        public Builder keycloak(KeycloakSettings settings) {
            this.keycloak = settings;
            return this;
        }

        public Builder openmetadata(OpenMetadataSettings settings) {
            this.om = settings;
            return this;
        }

        public IdentityPort build() {
            if (jdbi == null) throw new IllegalStateException("jdbi not provided");
            if (keycloak == null) throw new IllegalStateException("keycloak settings not provided");

            URL jwksUrl;
            try {
                jwksUrl = URI.create(keycloak.jwksUri()).toURL();
            } catch (MalformedURLException ex) {
                throw new IllegalStateException("Invalid jwksUri: " + keycloak.jwksUri(), ex);
            }

            JwksKeyResolver keyResolver = new JwksKeyResolver(jwksUrl, keycloak.jwksCacheTtl());
            JwtValidator validator = new JwtValidator(
                    keyResolver,
                    keycloak.issuerUri(),
                    keycloak.audience(),
                    keycloak.requiredClaims(),
                    keycloak.clockSkew());

            OpenMetadataUserClient omClient = null;
            if (om != null && om.enabled()) {
                omClient = new OpenMetadataUserClient(
                        om.baseUrl(),
                        om.botToken(),
                        om.connectTimeout(),
                        om.requestTimeout());
                log.info("identity: OM lookup enabled, baseUrl={}", om.baseUrl());
            } else {
                log.warn("identity: OM lookup disabled — om_user_id будет provisional UUID v5");
            }
            return new KeycloakIdentityPort(jdbi, validator, omClient, keycloak.groupsClaim());
        }
    }

    /** Структура для отвязки от {@code RdmmeshConfiguration} (нет cyclic-зависимости app→identity). */
    public record KeycloakSettings(
            String issuerUri,
            String jwksUri,
            String audience,
            String usernameClaim,
            String groupsClaim,
            List<String> requiredClaims,
            Duration jwksCacheTtl,
            Duration clockSkew) {}

    public record OpenMetadataSettings(
            String baseUrl,
            String botToken,
            Duration connectTimeout,
            Duration requestTimeout) {

        public boolean enabled() {
            return baseUrl != null
                    && !baseUrl.isBlank()
                    && botToken != null
                    && !botToken.isBlank();
        }
    }
}
