package bank.rdmmesh.authoring.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jdbi.v3.core.Jdbi;

import bank.rdmmesh.api.port.PublishedSnapshotPort;
import bank.rdmmesh.authoring.internal.dao.CodeItemDao;
import bank.rdmmesh.authoring.internal.dao.CodeItemDao.ItemRow;

/**
 * Реализация {@link PublishedSnapshotPort} над {@code authoring.code_item}. Алгоритм
 * канонизации (порядок полей, сортировка, сериализация) вынесен в {@link CanonicalSnapshot}
 * — тот же код использует relational store, поэтому {@code content_hash} совпадает.
 */
public final class PublishedSnapshotAdapter implements PublishedSnapshotPort {

    private final Jdbi jdbi;

    public PublishedSnapshotAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public byte[] canonicalSnapshotBytes(UUID versionId) {
        List<ItemRow> rows = jdbi.withExtension(CodeItemDao.class, dao -> dao.findAll(versionId));
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (ItemRow r : rows) {
            items.add(buildItem(r));
        }
        return CanonicalSnapshot.bytes(versionId.toString(), items);
    }

    private Map<String, Object> buildItem(ItemRow r) {
        return CanonicalSnapshot.item(
                CanonicalSnapshot.parseJson(r.keyPartsJson()),
                CanonicalSnapshot.parseJson(r.parentKeyJson()),
                CanonicalSnapshot.parseJson(r.parentRefJson()),
                r.labelRu(),
                r.labelEn(),
                r.descriptionRu(),
                r.descriptionEn(),
                CanonicalSnapshot.parseJson(r.attributesJson()),
                r.orderIndex(),
                r.status(),
                r.effectiveFrom() == null ? null : r.effectiveFrom().toString(),
                r.effectiveTo() == null ? null : r.effectiveTo().toString());
    }
}
