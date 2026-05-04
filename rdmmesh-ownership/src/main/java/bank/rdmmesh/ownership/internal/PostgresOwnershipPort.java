package bank.rdmmesh.ownership.internal;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.port.OwnershipPort;
import bank.rdmmesh.ownership.internal.dao.AssetOwnershipDao;
import bank.rdmmesh.ownership.internal.dao.AssetOwnershipDao.AssetOwnershipRow;
import bank.rdmmesh.spec.entity.AssetOwnership;

/**
 * Реализация {@link OwnershipPort} поверх {@code ownership.rdm_asset_ownership}.
 *
 * <p>Эпик E3 покрывает только {@link #assignProvisionalOwner(UUID, String, UUID)} —
 * это всё, что нужно catalog'у при создании CodeSet'а. Полный {@link #applyChangeEvent}
 * (HMAC-валидация webhook'а, идемпотентность по {@code source_event_id}, mapping
 * owners/experts/reviewers) делается эпиком E7. До этого момента метод бросает
 * {@link UnsupportedOperationException}, чтобы не молча работать в ошибочном пути.
 */
public final class PostgresOwnershipPort implements OwnershipPort {

    private static final Logger log = LoggerFactory.getLogger(PostgresOwnershipPort.class);

    private final Jdbi jdbi;

    public PostgresOwnershipPort(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public List<AssetOwnership> ownersOf(UUID assetId, String assetType) {
        return jdbi.withExtension(
                AssetOwnershipDao.class,
                dao -> dao.findByAsset(assetId, assetType).stream()
                        .map(PostgresOwnershipPort::toDto)
                        .toList());
    }

    @Override
    public Set<String> rolesOf(UUID assetId, UUID omUserId) {
        List<AssetOwnershipRow> rows = jdbi.withExtension(
                AssetOwnershipDao.class, dao -> dao.findRolesOf(assetId, omUserId));
        return rows.stream().map(AssetOwnershipRow::role).collect(Collectors.toSet());
    }

    @Override
    public AssetOwnership assignProvisionalOwner(
            UUID assetId, String assetType, UUID creatorOmUserId) {
        return jdbi.inTransaction(handle -> {
            AssetOwnershipDao dao = handle.attach(AssetOwnershipDao.class);
            // Идемпотентно: если запись уже есть с тем же (asset, user, role=OWNER),
            // upsert обновит флаги и сохранит assigned_at, иначе создаст новую.
            dao.upsert(
                    assetId,
                    assetType,
                    creatorOmUserId,
                    "OWNER",
                    /* isProvisional */ true,
                    /* assignedBy */ creatorOmUserId,
                    /* sourceEventId */ null);
            log.info("ownership: provisional OWNER asset_id={} asset_type={} user={}",
                    assetId, assetType, creatorOmUserId);
            return toDto(dao.findOne(assetId, assetType, creatorOmUserId, "OWNER")
                    .orElseThrow(() -> new IllegalStateException(
                            "upsert не оставил строки для " + assetId)));
        });
    }

    @Override
    public void applyChangeEvent(
            UUID assetId, String assetType, OwnershipDelta delta, String sourceEventId) {
        // E7. До этого момента — явный fail.
        throw new UnsupportedOperationException(
                "applyChangeEvent реализуется в эпике E7 (ownership webhook receiver)."
                        + " До тех пор используйте только assignProvisionalOwner.");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static AssetOwnership toDto(AssetOwnershipRow row) {
        AssetOwnership a = new AssetOwnership();
        a.setAssetId(row.assetId().toString());
        a.setAssetType(bank.rdmmesh.spec.entity.AssetOwnership.AssetType.fromValue(row.assetType()));
        a.setOmUserId(row.omUserId().toString());
        a.setRole(bank.rdmmesh.spec.entity.AssetOwnership.AssetRole.fromValue(row.role()));
        a.setIsProvisional(row.isProvisional());
        a.setAssignedAt(toIso(row.assignedAt()));
        a.setAssignedBy(row.assignedBy() == null ? null : row.assignedBy().toString());
        a.setSourceEventId(row.sourceEventId());
        return a;
    }

    private static String toIso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
