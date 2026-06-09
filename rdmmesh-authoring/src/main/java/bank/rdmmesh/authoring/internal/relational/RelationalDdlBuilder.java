package bank.rdmmesh.authoring.internal.relational;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Чистый генератор DDL для relational store: {@code CREATE TABLE} физической таблицы
 * справочника по списку ключевых и атрибутивных колонок.
 *
 * <p>Идентификаторы (имена таблиц/колонок) валидируются против snake_case-паттерна —
 * того же, что в spec для key-part'ов/атрибутов/имён CodeSet'а — поэтому SQL-инъекция
 * через имена невозможна; значения строк всегда биндятся параметрами (см.
 * {@code RelationalStoreService}).
 */
public final class RelationalDdlBuilder {

    /** snake_case identifier, ≤64 символов (как в spec key-spec/code-set). */
    public static final Pattern IDENT = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    /** Базовое имя {@code <domain>__<codeset>}, ≤54 — чтобы "<base>__current" укладывалось в 63. */
    public static final Pattern BASE_TABLE_IDENT = Pattern.compile("^[a-z][a-z0-9_]{0,53}$");

    /** Итоговое имя физической таблицы (draft/current), ≤63 — лимит идентификатора PG. */
    public static final Pattern TABLE_IDENT = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    public static final String DRAFT_SUFFIX = "__draft";
    public static final String CURRENT_SUFFIX = "__current";

    /** Колонка version_id draft-таблицы (часть PK черновика). */
    public static final Column VERSION_ID = new Column("version_id", "uuid", true);

    /** Стандартные колонки, которые есть у каждой материализованной таблицы. */
    private static final List<Column> STANDARD = List.of(
            new Column("label_ru", "text", false),
            new Column("label_en", "text", false),
            new Column("status", "text", true, "'ACTIVE'"),
            new Column("effective_from", "date", false),
            new Column("effective_to", "date", false));

    private RelationalDdlBuilder() {}

    /** Колонка физической таблицы. */
    public record Column(String name, String sqlType, boolean notNull, String defaultExpr) {
        public Column(String name, String sqlType, boolean notNull) {
            this(name, sqlType, notNull, null);
        }
    }

    public static boolean isValidIdentifier(String name) {
        return name != null && IDENT.matcher(name).matches();
    }

    /** Базовое имя {@code <domain>__<codeset>} с проверкой длины/charset (≤54). */
    public static String tableName(String domainName, String codesetName) {
        require(isValidIdentifier(domainName), "domain name: " + domainName);
        require(isValidIdentifier(codesetName), "codeset name: " + codesetName);
        String name = domainName + "__" + codesetName;
        require(
                BASE_TABLE_IDENT.matcher(name).matches(),
                "derived base table name too long (>54): " + name);
        return name;
    }

    public static String draftTable(String baseName) {
        return baseName + DRAFT_SUFFIX;
    }

    public static String currentTable(String baseName) {
        return baseName + CURRENT_SUFFIX;
    }

    /** key+attr колонки + стандартные (label/status/effective), дедуп по имени, с сохранением порядка. */
    public static List<Column> withStandard(List<Column> keyAndAttr) {
        Set<String> seen = new LinkedHashSet<>();
        List<Column> all = new ArrayList<>();
        for (Column c : keyAndAttr == null ? List.<Column>of() : keyAndAttr) {
            validateColumn(c);
            if (seen.add(c.name())) {
                all.add(c);
            }
        }
        for (Column c : STANDARD) {
            if (seen.add(c.name())) {
                all.add(c);
            }
        }
        return all;
    }

    /**
     * {@code CREATE TABLE IF NOT EXISTS "schema"."table" (...)} с PK по ключевым колонкам.
     * Стандартные колонки добавляются, если их имена не заняты ключом/атрибутом.
     * Используется для {@code __current}-таблицы (PK = ключи справочника).
     */
    public static String createTable(
            String schema,
            String table,
            List<Column> keyColumns,
            List<Column> attributeColumns) {
        require(keyColumns != null && !keyColumns.isEmpty(), "at least one key column required");
        List<Column> all = withStandard(concat(keyColumns, attributeColumns));
        List<String> pk = new ArrayList<>();
        for (Column c : keyColumns) {
            pk.add(c.name());
        }
        return createTableWithPk(schema, table, all, pk);
    }

    /** Общий генератор: рендерит все колонки в заданном порядке + PRIMARY KEY (pkColumns). */
    public static String createTableWithPk(
            String schema, String table, List<Column> columns, List<String> pkColumns) {
        require(isValidIdentifier(schema), "schema: " + schema);
        require(TABLE_IDENT.matcher(table).matches(), "table name invalid/too long: " + table);
        require(columns != null && !columns.isEmpty(), "at least one column required");
        require(pkColumns != null && !pkColumns.isEmpty(), "at least one PK column required");

        Set<String> names = new LinkedHashSet<>();
        for (Column c : columns) {
            validateColumn(c);
            require(names.add(c.name()), "duplicate column: " + c.name());
        }
        for (String pk : pkColumns) {
            require(names.contains(pk), "PK column not among columns: " + pk);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ")
                .append(q(schema)).append('.').append(q(table)).append(" (\n");
        for (Column c : columns) {
            sb.append("    ").append(q(c.name())).append(' ').append(c.sqlType());
            if (c.notNull()) {
                sb.append(" NOT NULL");
            }
            if (c.defaultExpr() != null) {
                sb.append(" DEFAULT ").append(c.defaultExpr());
            }
            sb.append(",\n");
        }
        sb.append("    PRIMARY KEY (");
        for (int i = 0; i < pkColumns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(q(pkColumns.get(i)));
        }
        sb.append(")\n)");
        return sb.toString();
    }

    private static List<Column> concat(List<Column> a, List<Column> b) {
        List<Column> out = new ArrayList<>(a);
        if (b != null) {
            out.addAll(b);
        }
        return out;
    }

    private static void validateColumn(Column c) {
        require(c != null, "null column");
        require(isValidIdentifier(c.name()), "column name: " + (c == null ? null : c.name()));
        // sqlType — из RelationalTypes (закрытый набор), но подстрахуемся.
        require(
                c.sqlType() != null && c.sqlType().matches("^[a-z][a-z ]{0,31}$"),
                "sql type: " + c.sqlType());
    }

    /** Двойные кавычки вокруг (уже провалидированного) идентификатора. */
    static String q(String ident) {
        return '"' + ident + '"';
    }

    private static void require(boolean cond, String msg) {
        if (!cond) {
            throw new IllegalArgumentException("relational DDL: " + msg);
        }
    }
}
