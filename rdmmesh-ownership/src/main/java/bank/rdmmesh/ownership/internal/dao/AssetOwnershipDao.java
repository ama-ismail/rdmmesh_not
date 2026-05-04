package bank.rdmmesh.ownership.internal.dao;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * DAO для {@code ownership.rdm_asset_ownership}. Upsert-семантика заточена под webhook
 * (UNIQUE по {@code (asset_id, asset_type, om_user_id, role)} + {@code source_event_id}).
 */
public interface AssetOwnershipDao {

    String COLUMNS =
            "id, asset_id, asset_type, om_user_id, role, is_provisional,"
                    + " assigned_at, assigned_by, source_event_id";

    @SqlQuery("SELECT " + COLUMNS
            + " FROM ownership.rdm_asset_ownership"
            + " WHERE asset_id = :assetId AND asset_type = :assetType")
    @RegisterConstructorMapper(AssetOwnershipRow.class)
    List<AssetOwnershipRow> findByAsset(
            @Bind("assetId") UUID assetId, @Bind("assetType") String assetType);

    @SqlQuery("SELECT " + COLUMNS
            + " FROM ownership.rdm_asset_ownership"
            + " WHERE asset_id = :assetId AND om_user_id = :omUserId")
    @RegisterConstructorMapper(AssetOwnershipRow.class)
    List<AssetOwnershipRow> findRolesOf(
            @Bind("assetId") UUID assetId, @Bind("omUserId") UUID omUserId);

    @SqlQuery("SELECT " + COLUMNS
            + " FROM ownership.rdm_asset_ownership"
            + " WHERE asset_id = :assetId AND asset_type = :assetType"
            + "   AND om_user_id = :omUserId AND role = :role")
    @RegisterConstructorMapper(AssetOwnershipRow.class)
    Optional<AssetOwnershipRow> findOne(
            @Bind("assetId") UUID assetId,
            @Bind("assetType") String assetType,
            @Bind("omUserId") UUID omUserId,
            @Bind("role") String role);

    /**
     * Upsert по уникальному ключу {@code (asset_id, asset_type, om_user_id, role)}. При
     * повторе с тем же {@code source_event_id} — no-op (idempotent webhook handling).
     */
    @SqlUpdate(
            """
            INSERT INTO ownership.rdm_asset_ownership
                (asset_id, asset_type, om_user_id, role, is_provisional,
                 assigned_by, source_event_id)
            VALUES (:assetId, :assetType, :omUserId, :role, :isProvisional,
                    :assignedBy, :sourceEventId)
            ON CONFLICT (asset_id, asset_type, om_user_id, role) DO UPDATE
              SET is_provisional   = EXCLUDED.is_provisional,
                  assigned_by      = COALESCE(EXCLUDED.assigned_by,    ownership.rdm_asset_ownership.assigned_by),
                  source_event_id  = COALESCE(EXCLUDED.source_event_id, ownership.rdm_asset_ownership.source_event_id),
                  assigned_at      = CASE
                      WHEN ownership.rdm_asset_ownership.is_provisional AND NOT EXCLUDED.is_provisional
                          THEN now()
                      ELSE ownership.rdm_asset_ownership.assigned_at END
            """)
    int upsert(
            @Bind("assetId") UUID assetId,
            @Bind("assetType") String assetType,
            @Bind("omUserId") UUID omUserId,
            @Bind("role") String role,
            @Bind("isProvisional") boolean isProvisional,
            @Bind("assignedBy") UUID assignedBy,
            @Bind("sourceEventId") String sourceEventId);

    @SqlUpdate(
            "DELETE FROM ownership.rdm_asset_ownership"
                    + " WHERE asset_id = :assetId AND asset_type = :assetType"
                    + "   AND om_user_id = :omUserId AND role = :role")
    int delete(
            @Bind("assetId") UUID assetId,
            @Bind("assetType") String assetType,
            @Bind("omUserId") UUID omUserId,
            @Bind("role") String role);

    record AssetOwnershipRow(
            UUID id,
            UUID assetId,
            String assetType,
            UUID omUserId,
            String role,
            boolean isProvisional,
            Instant assignedAt,
            UUID assignedBy,
            String sourceEventId) {}
}
