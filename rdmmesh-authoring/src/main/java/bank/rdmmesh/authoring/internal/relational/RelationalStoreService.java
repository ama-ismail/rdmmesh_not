package bank.rdmmesh.authoring.internal.relational;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.port.CatalogReadPort;
import bank.rdmmesh.authoring.internal.dao.PhysicalTableRegistryDao;
import bank.rdmmesh.authoring.internal.relational.RelationalDdlBuilder.Column;

/**
 * Relational store (спайк полной замены JSONB): материализует CodeSet в реальную
 * типизированную таблицу схемы {@code rd_data} и пишет/читает строки «ячейка за ячейкой».
 *
 * <p>Колонки выводятся из {@code key_spec} (ключевые, NOT NULL, в PK) и активной
 * CodeSetSchema (атрибуты). Имена идентификаторов валидируются snake_case-паттерном,
 * значения биндятся параметрами с явным {@code CAST(:p AS <type>)} — никакой
 * конкатенации значений в SQL.
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

    // ── public API ────────────────────────────────────────────────────────────

    /** Создаёт (IF NOT EXISTS) физическую таблицу справочника и регистрирует её. */
    public ProvisionResult provision(UUID codesetId) {
        ResolvedTable t = resolve(codesetId);
        String ddl = RelationalDdlBuilder.createTable(
                SCHEMA, t.table, t.keyColumns, t.attributeColumns);
        jdbi.useHandle(h -> {
            h.execute(ddl);
            h.attach(PhysicalTableRegistryDao.class)
                    .upsert(codesetId, SCHEMA, t.table, t.schemaVersion);
        });
        log.info("relational store: материализован codeset_id={} → {}.{} ({} колонок)",
                codesetId, SCHEMA, t.table, t.columnTypes.size());
        return new ProvisionResult(SCHEMA, t.table, new ArrayList<>(t.columnTypes.keySet()), ddl);
    }

    /** Вставляет/обновляет (upsert по ключу) одну строку — по ячейке на переданное значение. */
    public void upsertRow(UUID codesetId, Map<String, Object> cells) {
        ResolvedTable t = requireProvisioned(codesetId);
        validateCells(t, cells);
        jdbi.useHandle(h -> upsertRow(h, t, cells));
    }

    /**
     * Бэкфилл (Stage 2-lite): читает все CodeItem'ы версии из текущего хранилища
     * ({@code authoring.code_item}) и заливает их в физическую таблицу справочника
     * «ячейка за ячейкой». key_parts мапятся позиционно на ключевые колонки, attributes —
     * на атрибутивные, плюс label/status/effective_*. Идемпотентно (upsert по ключу),
     * физическая таблица при необходимости создаётся (provision).
     */
    public SyncResult syncFromVersion(UUID versionId) {
        UUID codesetId = jdbi.withHandle(h -> h.createQuery(
                        "SELECT codeset_id FROM authoring.code_set_version WHERE id = :v")
                .bind("v", versionId)
                .mapTo(UUID.class)
                .findOne())
                .orElseThrow(() -> new IllegalArgumentException("unknown version: " + versionId));

        provision(codesetId); // idempotent: гарантируем таблицу + реестр
        ResolvedTable t = resolve(codesetId);

        List<ItemRow> items = jdbi.withHandle(h -> h.createQuery(
                        """
                        SELECT key_parts::text   AS key_parts_json,
                               attributes::text  AS attributes_json,
                               label_ru, label_en, status,
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
                        rs.getString("label_ru"),
                        rs.getString("label_en"),
                        rs.getString("status"),
                        rs.getString("effective_from"),
                        rs.getString("effective_to")))
                .list());

        int[] loaded = {0};
        jdbi.useHandle(h -> {
            for (ItemRow item : items) {
                Map<String, Object> cells = toCells(t, item);
                validateCells(t, cells);
                upsertRow(h, t, cells);
                loaded[0]++;
            }
        });
        log.info("relational store: sync version_id={} → {}.{}: {} строк",
                versionId, SCHEMA, t.table, loaded[0]);
        return new SyncResult(codesetId, t.table, loaded[0]);
    }

    /** Возвращает все строки физической таблицы справочника как список map'ов. */
    public List<Map<String, Object>> listRows(UUID codesetId) {
        ResolvedTable t = requireProvisioned(codesetId);
        String sql = "SELECT * FROM " + RelationalDdlBuilder.q(SCHEMA) + '.'
                + RelationalDdlBuilder.q(t.table);
        return jdbi.withHandle(h -> h.createQuery(sql).mapToMap().list());
    }

    // ── row write helpers ─────────────────────────────────────────────────────

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

    /** Строит и выполняет INSERT ... ON CONFLICT (key) для одной строки в рамках handle. */
    private void upsertRow(org.jdbi.v3.core.Handle h, ResolvedTable t, Map<String, Object> cells) {
        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        StringBuilder updates = new StringBuilder();
        Map<String, Object> binds = new LinkedHashMap<>();
        int i = 0;
        for (Map.Entry<String, Object> e : cells.entrySet()) {
            Object value = e.getValue();
            if (value == null) {
                continue; // null → колонка остаётся DEFAULT/NULL
            }
            String name = e.getKey();
            String type = t.columnTypes.get(name);
            String p = "p" + i++;
            if (cols.length() > 0) {
                cols.append(", ");
                vals.append(", ");
            }
            cols.append(RelationalDdlBuilder.q(name));
            vals.append("CAST(:").append(p).append(" AS ").append(type).append(')');
            if (!t.keyNames.contains(name)) {
                if (updates.length() > 0) updates.append(", ");
                updates.append(RelationalDdlBuilder.q(name))
                        .append(" = EXCLUDED.").append(RelationalDdlBuilder.q(name));
            }
            binds.put(p, coerce(value, type));
        }

        StringBuilder keyList = new StringBuilder();
        for (String key : t.keyNames) {
            if (keyList.length() > 0) keyList.append(", ");
            keyList.append(RelationalDdlBuilder.q(key));
        }
        String onConflict = updates.length() == 0
                ? " ON CONFLICT (" + keyList + ") DO NOTHING"
                : " ON CONFLICT (" + keyList + ") DO UPDATE SET " + updates;

        String sql = "INSERT INTO " + RelationalDdlBuilder.q(SCHEMA) + '.'
                + RelationalDdlBuilder.q(t.table) + " (" + cols + ") VALUES (" + vals + ')'
                + onConflict;

        var update = h.createUpdate(sql);
        for (Map.Entry<String, Object> b : binds.entrySet()) {
            update.bind(b.getKey(), b.getValue());
        }
        update.execute();
    }

    /** CodeItem-строка (jsonb key_parts/attributes) → map колонка→значение физической таблицы. */
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
        putIfNotNull(cells, "label_ru", item.labelRu());
        putIfNotNull(cells, "label_en", item.labelEn());
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

    /** JsonNode → Java-скаляр для bind'а; объекты/массивы — как JSON-текст (для jsonb). */
    private static Object jsonToJava(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isTextual()) return n.asText();
        if (n.isBoolean()) return n.asBoolean();
        if (n.isIntegralNumber()) return n.asLong();
        if (n.isNumber()) return n.asDouble();
        return n.toString(); // object/array → JSON-текст
    }

    // ── internals ───────────────────────────────────────────────────────────────

    private ResolvedTable requireProvisioned(UUID codesetId) {
        Optional<PhysicalTableRegistryDao.PhysicalTableRow> reg = jdbi.withExtension(
                PhysicalTableRegistryDao.class, dao -> dao.findByCodeset(codesetId));
        if (reg.isEmpty()) {
            throw new IllegalStateException(
                    "codeset " + codesetId + " is not provisioned — call provision() first");
        }
        return resolve(codesetId);
    }

    /** Снимок CodeSet'а → имя таблицы и типизированные колонки (key + attributes + standard). */
    private ResolvedTable resolve(UUID codesetId) {
        CatalogReadPort.CodeSetSnapshot cs = catalog.findCodeSet(codesetId)
                .filter(s -> !s.deleted())
                .orElseThrow(() -> new IllegalArgumentException("unknown codeset: " + codesetId));
        String domainName = catalog.findDomain(cs.domainId())
                .map(CatalogReadPort.DomainSnapshot::name)
                .orElseThrow(() -> new IllegalStateException("domain not found: " + cs.domainId()));
        String table = RelationalDdlBuilder.tableName(domainName, cs.name());

        List<Column> keyColumns = parseKeyColumns(cs.keySpecJson());
        List<Column> attributeColumns = catalog.currentSchema(codesetId)
                .map(s -> parseAttributeColumns(s.jsonSchemaText()))
                .orElseGet(List::of);

        // Карта name → sqlType по всем колонкам (key + attr + standard), порядок сохраняем.
        Map<String, String> types = new LinkedHashMap<>();
        List<String> keyNames = new ArrayList<>();
        for (Column c : keyColumns) {
            types.put(c.name(), c.sqlType());
            keyNames.add(c.name());
        }
        for (Column c : attributeColumns) {
            types.putIfAbsent(c.name(), c.sqlType());
        }
        types.putIfAbsent("label_ru", "text");
        types.putIfAbsent("label_en", "text");
        types.putIfAbsent("status", "text");
        types.putIfAbsent("effective_from", "date");
        types.putIfAbsent("effective_to", "date");

        return new ResolvedTable(table, cs.schemaVersion(), keyColumns, attributeColumns, types, keyNames);
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

    /** "type" может быть строкой или union-массивом (берём первый не-"null"). */
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

    /** Приведение JSON-значения к bind-объекту. jsonb-колонки сериализуем в JSON-текст. */
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
            String table,
            int schemaVersion,
            List<Column> keyColumns,
            List<Column> attributeColumns,
            Map<String, String> columnTypes,
            List<String> keyNames) {}

    public record ProvisionResult(
            String schema, String table, List<String> columns, String ddl) {}

    public record SyncResult(UUID codesetId, String table, int rowsLoaded) {}

    /** Сырая CodeItem-строка из authoring.code_item для бэкфилла. */
    private record ItemRow(
            String keyPartsJson,
            String attributesJson,
            String labelRu,
            String labelEn,
            String status,
            String effectiveFrom,
            String effectiveTo) {}
}
