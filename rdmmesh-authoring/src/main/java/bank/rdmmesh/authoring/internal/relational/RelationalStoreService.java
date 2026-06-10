package bank.rdmmesh.authoring.internal.relational;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.eventbus.VersionPublishedDomainEvent;
import bank.rdmmesh.api.port.CatalogReadPort;
import bank.rdmmesh.authoring.internal.dao.PhysicalTableRegistryDao;
import bank.rdmmesh.authoring.internal.relational.RelationalDdlBuilder.Column;

/**
 * Relational store (спайк полной замены JSONB), модель версионности — вариант C:
 * на справочник две физические таблицы в схеме {@code rd_data}:
 * <ul>
 *   <li>{@code "<base>__draft"}   — рабочая область авторинга, PK {@code (version_id, <ключи>)};</li>
 *   <li>{@code "<base>__current"} — текущий PUBLISHED-снапшот, PK {@code (<ключи>)} — цель FK.</li>
 * </ul>
 *
 * <p>Write-path пишет в {@code __draft} по {@code version_id}; на publish {@code __current}
 * атомарно пересобирается из draft нужной версии. Колонки выводятся из {@code key_spec}
 * (ключи) и активной CodeSetSchema (атрибуты). Идентификаторы валидируются snake_case,
 * значения биндятся параметрами с {@code CAST(:p AS <type>)} — конкатенации значений нет.
 */
public final class RelationalStoreService {

    private static final Logger log = LoggerFactory.getLogger(RelationalStoreService.class);
    private static final String SCHEMA = "rd_data";

    private final Jdbi jdbi;
    private final CatalogReadPort catalog;
    private final ObjectMapper json;

    public RelationalStoreService(Jdbi jdbi, CatalogReadPort catalog, ObjectMapper json) {
        this.jdbi = jdbi;
        this.catalog = catalog;
        this.json = json;
    }

    // ── publish-хук (Stage 2-final) ───────────────────────────────────────────────

    /**
     * Подписка на {@link VersionPublishedDomainEvent}: после реального publish'а версии
     * (E6, {@code PublishingService}) пересобираем {@code __current} из {@code code_item}
     * этой версии. Регистрируется в {@code AuthoringModule.build} на том же in-process bus,
     * что и publishing.
     */
    public void registerOn(EventBus bus) {
        bus.subscribe(VersionPublishedDomainEvent.class, this::onVersionPublished);
    }

    /**
     * Пересборка {@code __current} после publish'а — post-commit, в собственной tx,
     * <b>best-effort</b> (SPEC §3.8: side-effect не должен ломать business-операцию;
     * вдобавок {@code SyncEventBus} изолирует исключения подписчиков). {@code code_item}
     * остаётся источником истины: {@link #syncFromVersion} бэкфиллит {@code __draft} из
     * него, {@link #publish} атомарно пересобирает из draft текущий снапшот.
     */
    void onVersionPublished(VersionPublishedDomainEvent event) {
        if (event.payload() == null || event.payload().getVersionId() == null) {
            return;
        }
        UUID versionId;
        try {
            versionId = UUID.fromString(event.payload().getVersionId());
        } catch (IllegalArgumentException e) {
            log.warn("relational store: bad version_id в VersionPublishedDomainEvent: {}",
                    event.payload().getVersionId());
            return;
        }
        try {
            syncFromVersion(versionId);
            PublishResult res = publish(versionId);
            log.info("relational store: __current пересобран после publish version_id={} ({} строк)",
                    versionId, res.rowsPublished());
        } catch (RuntimeException e) {
            log.warn("relational store: пересборка __current после publish version_id={} не удалась: {}",
                    versionId, e.toString());
        }
    }

    // ── provision ───────────────────────────────────────────────────────────────

    /** Создаёт (IF NOT EXISTS) обе таблицы справочника (draft + current) и регистрирует базу. */
    public ProvisionResult provision(UUID codesetId) {
        ResolvedTable t = resolve(codesetId);

        String currentDdl = RelationalDdlBuilder.createTableWithPk(
                SCHEMA, t.currentTable, t.dataColumns, t.keyNames);

        List<Column> draftColumns = new ArrayList<>();
        draftColumns.add(RelationalDdlBuilder.VERSION_ID);
        draftColumns.addAll(t.dataColumns);
        List<String> draftPk = new ArrayList<>();
        draftPk.add(RelationalDdlBuilder.VERSION_ID.name());
        draftPk.addAll(t.keyNames);
        String draftDdl = RelationalDdlBuilder.createTableWithPk(
                SCHEMA, t.draftTable, draftColumns, draftPk);

        jdbi.useHandle(h -> {
            h.execute(currentDdl);
            h.execute(draftDdl);
            // Stage 4-lite: идемпотентно доращиваем новые колонки на уже существующих
            // таблицах (description/parent_key/order_index, эволюция атрибутов).
            h.execute(RelationalDdlBuilder.addColumnsIfNotExists(SCHEMA, t.currentTable, t.dataColumns));
            h.execute(RelationalDdlBuilder.addColumnsIfNotExists(SCHEMA, t.draftTable, t.dataColumns));
            h.attach(PhysicalTableRegistryDao.class)
                    .upsert(codesetId, SCHEMA, t.base, t.schemaVersion);
        });
        log.info("relational store: материализован codeset_id={} → {}.{}__{{draft,current}}",
                codesetId, SCHEMA, t.base);
        return new ProvisionResult(
                SCHEMA, t.draftTable, t.currentTable, new ArrayList<>(t.columnTypes.keySet()));
    }

    // ── write-path (draft) ───────────────────────────────────────────────────────

    /** Upsert одной строки черновика {@code version_id} — по ячейке на переданное значение. */
    public void upsertDraftRow(UUID versionId, Map<String, Object> cells) {
        ResolvedTable t = requireProvisioned(codesetIdOf(versionId));
        validateCells(t, cells);
        jdbi.useHandle(h -> upsertDraft(h, t, versionId, cells));
    }

    /** Удаляет строку черновика по ключу (в {@code keyCells} должны быть все ключевые колонки). */
    public void deleteDraftRow(UUID versionId, Map<String, Object> keyCells) {
        ResolvedTable t = requireProvisioned(codesetIdOf(versionId));
        for (String key : t.keyNames) {
            if (keyCells == null || keyCells.get(key) == null) {
                throw new IllegalArgumentException("key column is required: " + key);
            }
        }
        StringBuilder where = new StringBuilder("version_id = CAST(:pv AS uuid)");
        Map<String, Object> binds = new LinkedHashMap<>();
        binds.put("pv", versionId);
        int i = 0;
        for (String key : t.keyNames) {
            String p = "k" + i++;
            where.append(" AND ").append(RelationalDdlBuilder.q(key))
                    .append(" = CAST(:").append(p).append(" AS ").append(t.columnTypes.get(key)).append(')');
            binds.put(p, coerce(keyCells.get(key), t.columnTypes.get(key)));
        }
        String sql = "DELETE FROM " + qTable(t.draftTable) + " WHERE " + where;
        jdbi.useHandle(h -> {
            var u = h.createUpdate(sql);
            binds.forEach(u::bind);
            u.execute();
        });
    }

    // ── live draft mirror (Stage 2-final, вызывается из AuthoringService) ──────────

    /**
     * Зеркалирует upsert одного item'а в {@code __draft}. Ключи берутся позиционно из
     * {@code keyParts} (порядок = key_spec.parts). Лениво материализует таблицы, если
     * справочник ещё не provisioned. Вызывается best-effort из {@code AuthoringService}
     * после успешной записи в {@code code_item}.
     */
    public void mirrorUpsertItem(
            UUID versionId,
            List<String> keyParts,
            Map<String, Object> attributes,
            List<String> parentKey,
            String labelRu,
            String labelEn,
            String descriptionRu,
            String descriptionEn,
            Integer orderIndex,
            String status,
            String effectiveFrom,
            String effectiveTo) {
        ResolvedTable t = ensureProvisioned(codesetIdOf(versionId));
        Map<String, Object> cells = itemCells(
                t, keyParts, attributes, parentKey, labelRu, labelEn,
                descriptionRu, descriptionEn, orderIndex, status, effectiveFrom, effectiveTo);
        validateCells(t, cells);
        jdbi.useHandle(h -> upsertDraft(h, t, versionId, cells));
    }

    /** Зеркалирует удаление item'а из {@code __draft} по ключу (позиционно из keyParts). */
    public void mirrorDeleteItem(UUID versionId, List<String> keyParts) {
        ResolvedTable t = ensureProvisioned(codesetIdOf(versionId));
        deleteDraftRow(versionId, keyCellsOf(t, keyParts));
    }

    /** Зеркалирует bulk-clear: удаляет все строки черновика версии из {@code __draft}. */
    public void mirrorClearDraft(UUID versionId) {
        ResolvedTable t = ensureProvisioned(codesetIdOf(versionId));
        jdbi.useHandle(h -> h.createUpdate(
                        "DELETE FROM " + qTable(t.draftTable) + " WHERE version_id = CAST(:v AS uuid)")
                .bind("v", versionId)
                .execute());
    }

    /**
     * Бэкфилл: читает все CodeItem'ы версии из {@code authoring.code_item} и заливает их
     * в {@code __draft} «ячейка за ячейкой». Идемпотентно (upsert по {@code (version_id,ключи)}).
     */
    public SyncResult syncFromVersion(UUID versionId) {
        UUID codesetId = codesetIdOf(versionId);
        provision(codesetId); // idempotent
        ResolvedTable t = resolve(codesetId);

        List<ItemRow> items = jdbi.withHandle(h -> h.createQuery(
                        """
                        SELECT key_parts::text   AS key_parts_json,
                               attributes::text  AS attributes_json,
                               parent_key::text  AS parent_key_json,
                               label_ru, label_en, description_ru, description_en,
                               order_index::text AS order_index, status,
                               effective_from::text AS effective_from,
                               effective_to::text   AS effective_to
                          FROM authoring.code_item
                         WHERE version_id = :v
                         ORDER BY order_index, id
                        """)
                .bind("v", versionId)
                .map((rs, ctx) -> new ItemRow(
                        rs.getString("key_parts_json"),
                        rs.getString("attributes_json"),
                        rs.getString("parent_key_json"),
                        rs.getString("label_ru"),
                        rs.getString("label_en"),
                        rs.getString("description_ru"),
                        rs.getString("description_en"),
                        rs.getString("order_index"),
                        rs.getString("status"),
                        rs.getString("effective_from"),
                        rs.getString("effective_to")))
                .list());

        int[] loaded = {0};
        jdbi.useHandle(h -> {
            for (ItemRow item : items) {
                Map<String, Object> cells = toCells(t, item);
                validateCells(t, cells);
                upsertDraft(h, t, versionId, cells);
                loaded[0]++;
            }
        });
        log.info("relational store: sync version_id={} → {}: {} строк", versionId, t.draftTable, loaded[0]);
        return new SyncResult(codesetId, t.draftTable, loaded[0]);
    }

    // ── publish ───────────────────────────────────────────────────────────────

    /** Пересобирает {@code __current} из draft указанной версии (атомарно) и фиксирует версию. */
    public PublishResult publish(UUID versionId) {
        UUID codesetId = codesetIdOf(versionId);
        ResolvedTable t = requireProvisioned(codesetId);

        StringBuilder cols = new StringBuilder();
        for (Column c : t.dataColumns) {
            if (cols.length() > 0) cols.append(", ");
            cols.append(RelationalDdlBuilder.q(c.name()));
        }
        String insertSelect = "INSERT INTO " + qTable(t.currentTable) + " (" + cols + ")"
                + " SELECT " + cols + " FROM " + qTable(t.draftTable)
                + " WHERE version_id = CAST(:v AS uuid)";

        int inserted = jdbi.inTransaction(h -> {
            h.createUpdate("DELETE FROM " + qTable(t.currentTable)).execute();
            int n = h.createUpdate(insertSelect).bind("v", versionId).execute();
            h.attach(PhysicalTableRegistryDao.class).setPublishedVersion(codesetId, versionId);
            return n;
        });
        log.info("relational store: publish version_id={} → {}: {} строк", versionId, t.currentTable, inserted);
        return new PublishResult(codesetId, t.currentTable, inserted);
    }

    // ── read ─────────────────────────────────────────────────────────────────────

    /** Все строки текущего PUBLISHED-снапшота ({@code __current}). */
    public List<Map<String, Object>> listCurrentRows(UUID codesetId) {
        ResolvedTable t = requireProvisioned(codesetId);
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM " + qTable(t.currentTable)).mapToMap().list());
    }

    /** Все строки черновика версии ({@code __draft WHERE version_id}). */
    public List<Map<String, Object>> listDraftRows(UUID versionId) {
        ResolvedTable t = requireProvisioned(codesetIdOf(versionId));
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT * FROM " + qTable(t.draftTable) + " WHERE version_id = CAST(:v AS uuid)")
                .bind("v", versionId)
                .mapToMap()
                .list());
    }

    // ── row write helper ─────────────────────────────────────────────────────────

    private void validateCells(ResolvedTable t, Map<String, Object> cells) {
        if (cells == null || cells.isEmpty()) {
            throw new IllegalArgumentException("row is empty");
        }
        for (String name : cells.keySet()) {
            if (!t.columnTypes.containsKey(name)) {
                throw new IllegalArgumentException("unknown column: " + name);
            }
        }
        for (String key : t.keyNames) {
            if (cells.get(key) == null) {
                throw new IllegalArgumentException("key column is required: " + key);
            }
        }
    }

    /** INSERT ... ON CONFLICT (version_id, ключи) для одной строки черновика. */
    private void upsertDraft(Handle h, ResolvedTable t, UUID versionId, Map<String, Object> cells) {
        StringBuilder cols = new StringBuilder(RelationalDdlBuilder.q("version_id"));
        StringBuilder vals = new StringBuilder("CAST(:pv AS uuid)");
        StringBuilder updates = new StringBuilder();
        Map<String, Object> binds = new LinkedHashMap<>();
        binds.put("pv", versionId);
        int i = 0;
        for (Map.Entry<String, Object> e : cells.entrySet()) {
            Object value = e.getValue();
            if (value == null) {
                continue;
            }
            String name = e.getKey();
            String type = t.columnTypes.get(name);
            String p = "p" + i++;
            cols.append(", ").append(RelationalDdlBuilder.q(name));
            vals.append(", CAST(:").append(p).append(" AS ").append(type).append(')');
            if (!t.keyNames.contains(name)) {
                if (updates.length() > 0) updates.append(", ");
                updates.append(RelationalDdlBuilder.q(name))
                        .append(" = EXCLUDED.").append(RelationalDdlBuilder.q(name));
            }
            binds.put(p, coerce(value, type));
        }

        StringBuilder keyList = new StringBuilder(RelationalDdlBuilder.q("version_id"));
        for (String key : t.keyNames) {
            keyList.append(", ").append(RelationalDdlBuilder.q(key));
        }
        String onConflict = updates.length() == 0
                ? " ON CONFLICT (" + keyList + ") DO NOTHING"
                : " ON CONFLICT (" + keyList + ") DO UPDATE SET " + updates;

        String sql = "INSERT INTO " + qTable(t.draftTable) + " (" + cols + ") VALUES (" + vals + ')'
                + onConflict;
        var update = h.createUpdate(sql);
        binds.forEach(update::bind);
        update.execute();
    }

    private static String qTable(String table) {
        return RelationalDdlBuilder.q(SCHEMA) + '.' + RelationalDdlBuilder.q(table);
    }

    // ── resolve ───────────────────────────────────────────────────────────────

    private UUID codesetIdOf(UUID versionId) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT codeset_id FROM authoring.code_set_version WHERE id = :v")
                .bind("v", versionId)
                .mapTo(UUID.class)
                .findOne())
                .orElseThrow(() -> new IllegalArgumentException("unknown version: " + versionId));
    }

    private ResolvedTable requireProvisioned(UUID codesetId) {
        Optional<PhysicalTableRegistryDao.PhysicalTableRow> reg = jdbi.withExtension(
                PhysicalTableRegistryDao.class, dao -> dao.findByCodeset(codesetId));
        if (reg.isEmpty()) {
            throw new IllegalStateException(
                    "codeset " + codesetId + " is not provisioned — call provision() first");
        }
        return resolve(codesetId);
    }

    /** Как {@link #requireProvisioned}, но при отсутствии — лениво материализует (idempotent). */
    private ResolvedTable ensureProvisioned(UUID codesetId) {
        boolean exists = jdbi.withExtension(PhysicalTableRegistryDao.class,
                dao -> dao.findByCodeset(codesetId)).isPresent();
        if (!exists) {
            provision(codesetId);
        }
        return resolve(codesetId);
    }

    /** Снимок CodeSet'а → имена таблиц и типизированные колонки. */
    private ResolvedTable resolve(UUID codesetId) {
        CatalogReadPort.CodeSetSnapshot cs = catalog.findCodeSet(codesetId)
                .filter(s -> !s.deleted())
                .orElseThrow(() -> new IllegalArgumentException("unknown codeset: " + codesetId));
        String domainName = catalog.findDomain(cs.domainId())
                .map(CatalogReadPort.DomainSnapshot::name)
                .orElseThrow(() -> new IllegalStateException("domain not found: " + cs.domainId()));
        String base = RelationalDdlBuilder.tableName(domainName, cs.name());

        List<Column> keyColumns = parseKeyColumns(cs.keySpecJson());
        List<Column> attributeColumns = catalog.currentSchema(codesetId)
                .map(s -> parseAttributeColumns(s.jsonSchemaText()))
                .orElseGet(List::of);

        List<Column> keyAndAttr = new ArrayList<>(keyColumns);
        keyAndAttr.addAll(attributeColumns);
        List<Column> dataColumns = RelationalDdlBuilder.withStandard(keyAndAttr);

        Map<String, String> types = new LinkedHashMap<>();
        for (Column c : dataColumns) {
            types.put(c.name(), c.sqlType());
        }
        List<String> keyNames = new ArrayList<>();
        for (Column c : keyColumns) {
            keyNames.add(c.name());
        }

        return new ResolvedTable(
                base,
                RelationalDdlBuilder.draftTable(base),
                RelationalDdlBuilder.currentTable(base),
                cs.schemaVersion(),
                dataColumns,
                types,
                keyNames);
    }

    private List<Column> parseKeyColumns(String keySpecJson) {
        if (keySpecJson == null || keySpecJson.isBlank()) {
            throw new IllegalStateException("codeset has no key_spec");
        }
        try {
            JsonNode parts = json.readTree(keySpecJson).path("parts");
            List<Column> out = new ArrayList<>();
            for (JsonNode part : parts) {
                String name = part.path("name").asText(null);
                String type = part.path("type").asText("STRING");
                out.add(new Column(name, RelationalTypes.keyPartSqlType(type), true));
            }
            if (out.isEmpty()) {
                throw new IllegalStateException("key_spec.parts is empty");
            }
            return out;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("cannot parse key_spec: " + keySpecJson, e);
        }
    }

    private List<Column> parseAttributeColumns(String jsonSchemaText) {
        if (jsonSchemaText == null || jsonSchemaText.isBlank()) {
            return List.of();
        }
        try {
            JsonNode props = json.readTree(jsonSchemaText).path("properties");
            List<Column> out = new ArrayList<>();
            props.fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                JsonNode def = entry.getValue();
                String type = jsonTypeOf(def);
                String format = def.path("format").asText(null);
                boolean hasEnum = def.has("enum");
                out.add(new Column(name, RelationalTypes.jsonSchemaSqlType(type, format, hasEnum), false));
            });
            return out;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("cannot parse json_schema: " + jsonSchemaText, e);
        }
    }

    private static String jsonTypeOf(JsonNode def) {
        JsonNode type = def.path("type");
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (!"null".equals(t.asText())) {
                    return t.asText();
                }
            }
            return "string";
        }
        return type.asText("string");
    }

    /** Item-поля (keyParts позиционно + атрибуты + label/status/effective) → map колонка→значение. */
    private Map<String, Object> itemCells(
            ResolvedTable t,
            List<String> keyParts,
            Map<String, Object> attributes,
            List<String> parentKey,
            String labelRu,
            String labelEn,
            String descriptionRu,
            String descriptionEn,
            Integer orderIndex,
            String status,
            String effectiveFrom,
            String effectiveTo) {
        Map<String, Object> cells = keyCellsOf(t, keyParts);
        if (attributes != null) {
            attributes.forEach((name, value) -> {
                if (t.columnTypes.containsKey(name) && !t.keyNames.contains(name)) {
                    cells.put(name, value);
                }
            });
        }
        // parent_key — jsonb-массив (coerce сериализует List в JSON-текст).
        putIfNotNull(cells, "parent_key", parentKey);
        putIfNotNull(cells, "label_ru", labelRu);
        putIfNotNull(cells, "label_en", labelEn);
        putIfNotNull(cells, "description_ru", descriptionRu);
        putIfNotNull(cells, "description_en", descriptionEn);
        putIfNotNull(cells, "order_index", orderIndex);
        putIfNotNull(cells, "status", status);
        putIfNotNull(cells, "effective_from", effectiveFrom);
        putIfNotNull(cells, "effective_to", effectiveTo);
        return cells;
    }

    /** Позиционная раскладка {@code keyParts} в map ключевая_колонка→значение. */
    private Map<String, Object> keyCellsOf(ResolvedTable t, List<String> keyParts) {
        if (keyParts == null || keyParts.size() != t.keyNames.size()) {
            throw new IllegalArgumentException("key_parts arity "
                    + (keyParts == null ? "null" : keyParts.size())
                    + " != key columns " + t.keyNames.size());
        }
        Map<String, Object> cells = new LinkedHashMap<>();
        for (int i = 0; i < t.keyNames.size(); i++) {
            cells.put(t.keyNames.get(i), keyParts.get(i));
        }
        return cells;
    }

    /** CodeItem-строка (jsonb key_parts/attributes) → map колонка→значение. */
    private Map<String, Object> toCells(ResolvedTable t, ItemRow item) {
        Map<String, Object> cells = new LinkedHashMap<>();
        try {
            JsonNode keyParts = json.readTree(item.keyPartsJson());
            if (!keyParts.isArray() || keyParts.size() != t.keyNames.size()) {
                throw new IllegalStateException(
                        "key_parts arity " + keyParts.size() + " != key columns " + t.keyNames.size());
            }
            for (int i = 0; i < t.keyNames.size(); i++) {
                cells.put(t.keyNames.get(i), jsonToJava(keyParts.get(i)));
            }
            if (item.attributesJson() != null && !item.attributesJson().isBlank()) {
                JsonNode attrs = json.readTree(item.attributesJson());
                attrs.fields().forEachRemaining(en -> {
                    if (t.columnTypes.containsKey(en.getKey()) && !t.keyNames.contains(en.getKey())) {
                        cells.put(en.getKey(), jsonToJava(en.getValue()));
                    }
                });
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("cannot parse code_item json", e);
        }
        putIfNotNull(cells, "parent_key", item.parentKeyJson());
        putIfNotNull(cells, "label_ru", item.labelRu());
        putIfNotNull(cells, "label_en", item.labelEn());
        putIfNotNull(cells, "description_ru", item.descriptionRu());
        putIfNotNull(cells, "description_en", item.descriptionEn());
        putIfNotNull(cells, "order_index", item.orderIndex());
        putIfNotNull(cells, "status", item.status());
        putIfNotNull(cells, "effective_from", item.effectiveFrom());
        putIfNotNull(cells, "effective_to", item.effectiveTo());
        return cells;
    }

    private static void putIfNotNull(Map<String, Object> cells, String key, Object value) {
        if (value != null) {
            cells.put(key, value);
        }
    }

    private static Object jsonToJava(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isTextual()) return n.asText();
        if (n.isBoolean()) return n.asBoolean();
        if (n.isIntegralNumber()) return n.asLong();
        if (n.isNumber()) return n.asDouble();
        return n.toString();
    }

    /** jsonb-колонки сериализуем в JSON-текст; остальное — как есть. */
    private Object coerce(Object value, String sqlType) {
        if ("jsonb".equals(sqlType) && !(value instanceof String)) {
            try {
                return json.writeValueAsString(value);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("cannot serialise jsonb cell", e);
            }
        }
        return value;
    }

    // ── DTO ───────────────────────────────────────────────────────────────────

    private record ResolvedTable(
            String base,
            String draftTable,
            String currentTable,
            int schemaVersion,
            List<Column> dataColumns,
            Map<String, String> columnTypes,
            List<String> keyNames) {}

    public record ProvisionResult(
            String schema, String draftTable, String currentTable, List<String> columns) {}

    public record SyncResult(UUID codesetId, String draftTable, int rowsLoaded) {}

    public record PublishResult(UUID codesetId, String currentTable, int rowsPublished) {}

    private record ItemRow(
            String keyPartsJson,
            String attributesJson,
            String parentKeyJson,
            String labelRu,
            String labelEn,
            String descriptionRu,
            String descriptionEn,
            String orderIndex,
            String status,
            String effectiveFrom,
            String effectiveTo) {}
}
