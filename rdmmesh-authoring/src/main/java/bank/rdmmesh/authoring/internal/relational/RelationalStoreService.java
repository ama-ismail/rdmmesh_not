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
        if (cells == null || cells.isEmpty()) {
            throw new IllegalArgumentException("row is empty");
        }
        // Валидация: все ключи известны таблице, все ключевые колонки заданы и не null.
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

        jdbi.useHandle(h -> {
            var update = h.createUpdate(sql);
            for (Map.Entry<String, Object> b : binds.entrySet()) {
                update.bind(b.getKey(), b.getValue());
            }
            update.execute();
        });
    }

    /** Возвращает все строки физической таблицы справочника как список map'ов. */
    public List<Map<String, Object>> listRows(UUID codesetId) {
        ResolvedTable t = requireProvisioned(codesetId);
        String sql = "SELECT * FROM " + RelationalDdlBuilder.q(SCHEMA) + '.'
                + RelationalDdlBuilder.q(t.table);
        return jdbi.withHandle(h -> h.createQuery(sql).mapToMap().list());
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
}
