package bank.rdmmesh.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import bank.rdmmesh.api.port.IdentityPort;
import bank.rdmmesh.identity.internal.dao.UserMappingDao;
import bank.rdmmesh.identity.internal.dao.UserMappingDao.UserMappingRow;
import bank.rdmmesh.identity.internal.jwt.JwtValidator;
import bank.rdmmesh.identity.internal.om.OpenMetadataUserClient;

/**
 * Реализация {@link IdentityPort} поверх Keycloak JWT и OpenMetadata REST.
 *
 * <p>Алгоритм {@link #authenticate(String)}:
 *
 * <ol>
 *   <li>Валидация JWT через {@link JwtValidator} (signature/iss/aud/exp + required claims).
 *   <li>Lookup в {@code identity.rdm_user_mapping} по {@code keycloak_sub} — fast path.
 *   <li>На cache miss + наличии OM-клиента — REST в OM по {@code preferred_username}, попадание
 *       в БД через {@code upsert}.
 *   <li>Если OM нет / не нашёл — fallback на deterministic UUID v5 от {@code (rdm_namespace, sub)}
 *       и provisional-запись в mapping. SPEC §2.4: provisional не блокирует логин, но фиксируется
 *       как {@code owner_was_provisional} в audit при последующих публикациях.
 * </ol>
 *
 * <p>Решения {@link #resolveOmUserId(UUID)} / {@link #resolveKeycloakSub(UUID)} — read-only из БД,
 * используются background-воркерами и webhook-приёмником OM (см. {@code rdmmesh-ownership}).
 *
 * <p>Класс thread-safe, держится в одном экземпляре на инстанс сервиса (создаётся в
 * {@code IdentityModule}).
 */
public final class KeycloakIdentityPort implements IdentityPort {

    private static final Logger log = LoggerFactory.getLogger(KeycloakIdentityPort.class);

    /** Namespace для deterministic provisional UUID v5: random-once UUID, прибит для воспроизводимости. */
    private static final UUID RDM_PROVISIONAL_NAMESPACE =
            UUID.fromString("c5b1a4e1-7c00-4e2c-9c8b-2c0c2c8a6f10");

    private final Jdbi jdbi;
    private final JwtValidator jwtValidator;
    private final OpenMetadataUserClient omClient;
    private final String groupsClaim;
    private final Cache<UUID, AuthenticatedUser> authCache;

    public KeycloakIdentityPort(
            Jdbi jdbi,
            JwtValidator jwtValidator,
            OpenMetadataUserClient omClient,
            String groupsClaim) {
        this.jdbi = jdbi;
        this.jwtValidator = jwtValidator;
        this.omClient = omClient;
        this.groupsClaim = groupsClaim;
        // Кэш именно по keycloak_sub, а не по token — токены короткоживущие, sub стабилен.
        // Размер ограничен — типовой банковский домен ≤ 50k активных юзеров; 10k — запас.
        this.authCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .build();
    }

    @Override
    public AuthenticatedUser authenticate(String bearerToken) {
        var resolved = jwtValidator.validate(bearerToken);
        return authCache.get(resolved.subject(), sub -> resolve(resolved));
    }

    private AuthenticatedUser resolve(JwtValidator.Resolved resolved) {
        UUID keycloakSub = resolved.subject();
        String resolvedUsername = resolved.preferredUsername();
        // Хотя preferred_username включён в requiredClaims — оставим safety-net.
        final String username =
                (resolvedUsername == null || resolvedUsername.isBlank())
                        ? "unknown@" + keycloakSub
                        : resolvedUsername;

        Optional<UserMappingRow> existing = jdbi.withExtension(
                UserMappingDao.class, dao -> dao.findByKeycloakSub(keycloakSub));
        if (existing.isPresent()) {
            jdbi.useExtension(UserMappingDao.class, dao -> dao.touchLastSeen(keycloakSub));
            return materialize(existing.get(), resolved);
        }

        UUID omUserId = lookupOrProvisionalOmUserId(username, keycloakSub);
        jdbi.useExtension(UserMappingDao.class, dao -> dao.upsert(
                omUserId,
                keycloakSub,
                username,
                resolved.email(),
                resolved.displayName()));
        log.info("identity: новый пользователь username={} keycloak_sub={} om_user_id={}",
                username, keycloakSub, omUserId);
        return new AuthenticatedUser(omUserId, keycloakSub, username, resolved.groups());
    }

    private UUID lookupOrProvisionalOmUserId(String username, UUID keycloakSub) {
        if (omClient != null) {
            Optional<UUID> fromOm = omClient.findUserIdByName(username);
            if (fromOm.isPresent()) {
                return fromOm.get();
            }
            log.warn("identity: OM не вернул user.id для {}; ставлю provisional UUID", username);
        } else {
            log.debug("identity: OM-клиент не настроен; provisional UUID для {}", username);
        }
        return provisionalUuid(keycloakSub);
    }

    private static UUID provisionalUuid(UUID keycloakSub) {
        // Detached UUID v5: namespace ⊕ keycloak_sub. Если в будущем OM найдётся — replace
        // через UPDATE SET om_user_id = real (разрешено в SPEC §2.4 reconciliation).
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(toBytes(RDM_PROVISIONAL_NAMESPACE));
            md.update(keycloakSub.toString().getBytes(StandardCharsets.UTF_8));
            byte[] hash = md.digest();
            hash[6] = (byte) ((hash[6] & 0x0F) | 0x50);   // version 5
            hash[8] = (byte) ((hash[8] & 0x3F) | 0x80);   // variant RFC4122
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) msb = (msb << 8) | (hash[i] & 0xff);
            for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (hash[i] & 0xff);
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    private static byte[] toBytes(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) out[i] = (byte) (msb >>> (56 - i * 8));
        for (int i = 0; i < 8; i++) out[8 + i] = (byte) (lsb >>> (56 - i * 8));
        return out;
    }

    private AuthenticatedUser materialize(UserMappingRow row, JwtValidator.Resolved resolved) {
        Set<String> groups = resolved.groups();
        return new AuthenticatedUser(row.omUserId(), row.keycloakSub(), row.username(), groups);
    }

    @Override
    public Optional<UUID> resolveOmUserId(UUID keycloakSub) {
        return jdbi.withExtension(
                        UserMappingDao.class, dao -> dao.findByKeycloakSub(keycloakSub))
                .map(UserMappingRow::omUserId);
    }

    @Override
    public Optional<UUID> resolveKeycloakSub(UUID omUserId) {
        return jdbi.withExtension(UserMappingDao.class, dao -> dao.findByOmUserId(omUserId))
                .map(UserMappingRow::keycloakSub);
    }

    /** Имя claim'а с группами (из конфига); экспонируется для wiring с Dropwizard-auth filter. */
    public String groupsClaim() {
        return groupsClaim;
    }

    /** Сбросить кэш аутентификации (например, при смене ownership через OM webhook). */
    public void invalidateAuthCache() {
        authCache.invalidateAll();
    }

    public void invalidateAuthCache(UUID keycloakSub) {
        authCache.invalidate(keycloakSub);
    }
}
