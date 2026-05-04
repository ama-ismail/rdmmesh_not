package bank.rdmmesh.identity.internal.dao;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * DAO для {@code identity.rdm_user_mapping}. Маппинг пользователей между Keycloak (sub) и
 * OpenMetadata (User.id) — заполняется лениво при первом успешном JWT-флоу (см. SPEC §2.4).
 *
 * <p>Все вызовы — через {@code Jdbi.useExtension(UserMappingDao.class, ...)} либо через
 * {@code Handle.attach(...)}, чтобы транзакции контролировались выше.
 */
public interface UserMappingDao {

    @SqlQuery(
            "SELECT om_user_id, keycloak_sub, username, email, display_name,"
                    + " first_seen_at, last_seen_at"
                    + " FROM identity.rdm_user_mapping WHERE keycloak_sub = :keycloakSub")
    @RegisterConstructorMapper(UserMappingRow.class)
    Optional<UserMappingRow> findByKeycloakSub(@Bind("keycloakSub") UUID keycloakSub);

    @SqlQuery(
            "SELECT om_user_id, keycloak_sub, username, email, display_name,"
                    + " first_seen_at, last_seen_at"
                    + " FROM identity.rdm_user_mapping WHERE om_user_id = :omUserId")
    @RegisterConstructorMapper(UserMappingRow.class)
    Optional<UserMappingRow> findByOmUserId(@Bind("omUserId") UUID omUserId);

    @SqlQuery(
            "SELECT om_user_id, keycloak_sub, username, email, display_name,"
                    + " first_seen_at, last_seen_at"
                    + " FROM identity.rdm_user_mapping WHERE lower(username) = lower(:username)")
    @RegisterConstructorMapper(UserMappingRow.class)
    Optional<UserMappingRow> findByUsername(@Bind("username") String username);

    /**
     * Идемпотентная вставка: если уже есть строка с тем же {@code keycloak_sub}, обновляет
     * last_seen_at + display_name/email (на случай переименования). PK-конфликт по
     * {@code om_user_id} тоже обрабатывается — это фактически тот же пользователь.
     */
    @SqlUpdate(
            """
            INSERT INTO identity.rdm_user_mapping
                (om_user_id, keycloak_sub, username, email, display_name)
            VALUES (:omUserId, :keycloakSub, :username, :email, :displayName)
            ON CONFLICT (keycloak_sub) DO UPDATE
              SET last_seen_at = now(),
                  email        = COALESCE(EXCLUDED.email, identity.rdm_user_mapping.email),
                  display_name = COALESCE(EXCLUDED.display_name, identity.rdm_user_mapping.display_name)
            """)
    int upsert(
            @Bind("omUserId") UUID omUserId,
            @Bind("keycloakSub") UUID keycloakSub,
            @Bind("username") String username,
            @Bind("email") String email,
            @Bind("displayName") String displayName);

    @SqlUpdate(
            "UPDATE identity.rdm_user_mapping SET last_seen_at = now()"
                    + " WHERE keycloak_sub = :keycloakSub")
    int touchLastSeen(@Bind("keycloakSub") UUID keycloakSub);

    /** Read-projection для row mapper. */
    record UserMappingRow(
            UUID omUserId,
            UUID keycloakSub,
            String username,
            String email,
            String displayName,
            Instant firstSeenAt,
            Instant lastSeenAt) {}
}
