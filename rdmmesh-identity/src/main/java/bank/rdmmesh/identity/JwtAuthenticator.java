package bank.rdmmesh.identity;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.port.IdentityPort;
import bank.rdmmesh.identity.internal.jwt.JwtValidator.InvalidJwtException;
import io.dropwizard.auth.AuthenticationException;
import io.dropwizard.auth.Authenticator;

/**
 * {@link Authenticator} для {@link io.dropwizard.auth.oauth.OAuthCredentialAuthFilter}.
 * Делегирует валидацию в {@link IdentityPort}; невалидные токены превращаются в пустой
 * {@link Optional} (Dropwizard вернёт 401 клиенту), unexpected runtime-ошибки бросаются как
 * {@link AuthenticationException} (Dropwizard вернёт 500).
 */
public final class JwtAuthenticator implements Authenticator<String, RdmmeshPrincipal> {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticator.class);

    private final IdentityPort identity;

    public JwtAuthenticator(IdentityPort identity) {
        this.identity = identity;
    }

    @Override
    public Optional<RdmmeshPrincipal> authenticate(String credentials) throws AuthenticationException {
        try {
            return Optional.of(new RdmmeshPrincipal(identity.authenticate(credentials)));
        } catch (InvalidJwtException invalid) {
            // Жёлтый путь: токен битый/просроченный/wrong issuer. Не логируем тело токена.
            log.debug("JWT отвергнут: {}", invalid.getMessage());
            return Optional.empty();
        } catch (RuntimeException unexpected) {
            log.error("identity: внутренняя ошибка при валидации JWT", unexpected);
            throw new AuthenticationException("internal error during JWT validation", unexpected);
        }
    }
}
