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

    /** Имя физической таблицы: {@code <domain>__<codeset>}, ≤63 символа (лимит реестра). */
    public static final Pattern TABLE_IDENT = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

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

    /** {@code <domain>__<codeset>} с проверкой длины/charset. */
    public static String tableName(String domainName, String codesetName) {
        require(isValidIdentifier(domainName), "domain name: " + domainName);
        require(isValidIdentifier(codesetName), "codeset name: " + codesetName);
        String name = domainName + "__" + codesetName;
        require(
                TABLE_IDENT.matcher(name).matches(),
                "derived table name too long (>63): " + name);
        return name;
    }

    /**
     * {@code CREATE TABLE IF NOT EXISTS "schema"."table" (...)} с PK по ключевым колонкам.
     * Стандартные колонки добавляются, если их имена не заняты ключом/атрибутом.
     */
    public static String createTable(
            String schema,
            String table,
            List<Column> keyColumns,
            List<Column> attributeColumns) {
        require(isValidIdentifier(schema), "schema: " + schema);
        require(TABLE_IDENT.matcher(table).matches(), "table: " + table);
        require(keyColumns != null && !keyColumns.isEmpty(), "at least one key column required");

        Set<String> seen = new LinkedHashSet<>();
        List<Column> all = new ArrayList<>();
        for (Column c : keyColumns) {
            validateColumn(c);
            require(seen.add(c.name()), "duplicate column: " + c.name());
            all.add(c);
        }
        for (Column c : attributeColumns == null ? List.<Column>of() : attributeColumns) {
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

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ")
                .append(q(schema)).append('.').append(q(table)).append(" (\n");
        for (Column c : all) {
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
        for (int i = 0; i < keyColumns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(q(keyColumns.get(i).name()));
        }
        sb.append(")\n)");
        return sb.toString();
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
