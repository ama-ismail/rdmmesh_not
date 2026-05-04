package bank.rdmmesh.identity.internal.jwt;

import java.net.URL;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.UrlJwkProvider;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Получает RSA-ключи из JWKS endpoint Keycloak с Caffeine-кэшированием. TTL — настраиваемый
 * (по дефолту 10 минут, см. handoff E2 §6). Concurrent get'ы по одному kid дедуплицируются
 * Caffeine'овской загрузкой.
 *
 * <p>На стороне приложения используется единственный экземпляр на инстанс сервиса (создаётся
 * в {@code IdentityModule}). Класс thread-safe.
 */
public final class JwksKeyResolver {

    private static final Logger log = LoggerFactory.getLogger(JwksKeyResolver.class);

    private final JwkProvider delegate;
    private final Cache<String, RSAPublicKey> keyCache;

    public JwksKeyResolver(URL jwksUrl, Duration ttl) {
        this(new UrlJwkProvider(Objects.requireNonNull(jwksUrl, "jwksUrl")), ttl);
    }

    /** Доступ для тестов: можно подменить {@link JwkProvider} на in-memory реализацию. */
    JwksKeyResolver(JwkProvider delegate, Duration ttl) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("TTL must be positive, got " + ttl);
        }
        this.keyCache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(64)
                .recordStats()
                .build();
    }

    /**
     * Возвращает RSA публичный ключ для указанного {@code kid}. Бросает {@link JwkException}
     * при недоступности JWKS endpoint или несовместимом типе ключа.
     */
    public RSAPublicKey getRsaKey(String kid) throws JwkException {
        var cached = keyCache.getIfPresent(kid);
        if (cached != null) {
            return cached;
        }
        Jwk jwk = delegate.get(kid);
        var publicKey = jwk.getPublicKey();
        if (!(publicKey instanceof RSAPublicKey rsa)) {
            throw new JwkException(
                    "JWK with kid=" + kid + " is not RSA: " + publicKey.getAlgorithm());
        }
        keyCache.put(kid, rsa);
        log.debug("Cached JWK kid={} (cache size={})", kid, keyCache.estimatedSize());
        return rsa;
    }

    /** Вытесняет ключ — на случай явной key rotation. */
    public void invalidate(String kid) {
        keyCache.invalidate(kid);
    }

    /** Полная инвалидация — для оперативного обновления в incident response. */
    public void invalidateAll() {
        keyCache.invalidateAll();
    }
}
