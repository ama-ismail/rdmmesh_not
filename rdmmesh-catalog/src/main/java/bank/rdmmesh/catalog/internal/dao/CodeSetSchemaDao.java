package bank.rdmmesh.catalog.internal.dao;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * DAO для {@code catalog.code_set_schema}. Хранит JSON Schema-документы поверх
 * CodeItem.attributes per CodeSet с monotonically growing {@code version}. Активная
 * версия — {@code catalog.code_set.schema_version} (см. {@link CodeSetDao#bumpSchemaVersion}).
 */
public interface CodeSetSchemaDao {

    @SqlQuery(
            "SELECT id, codeset_id, version, json_schema::text AS json_schema_text,"
                    + " created_at, created_by"
                    + " FROM catalog.code_set_schema"
                    + " WHERE codeset_id = :codesetId AND version = :version")
    @RegisterConstructorMapper(SchemaRow.class)
    Optional<SchemaRow> findByCodesetAndVersion(
            @Bind("codesetId") UUID codesetId, @Bind("version") int version);

    @SqlQuery(
            "SELECT id, codeset_id, version, json_schema::text AS json_schema_text,"
                    + " created_at, created_by"
                    + " FROM catalog.code_set_schema"
                    + " WHERE codeset_id = :codesetId ORDER BY version")
    @RegisterConstructorMapper(SchemaRow.class)
    List<SchemaRow> findByCodeset(@Bind("codesetId") UUID codesetId);

    @SqlQuery(
            "SELECT COALESCE(MAX(version), 0) FROM catalog.code_set_schema"
                    + " WHERE codeset_id = :codesetId")
    int maxVersion(@Bind("codesetId") UUID codesetId);

    @SqlUpdate(
            """
            INSERT INTO catalog.code_set_schema (id, codeset_id, version, json_schema, created_by)
            VALUES (:id, :codesetId, :version, CAST(:jsonSchemaText AS jsonb), :createdBy)
            """)
    int insert(
            @Bind("id") UUID id,
            @Bind("codesetId") UUID codesetId,
            @Bind("version") int version,
            @Bind("jsonSchemaText") String jsonSchemaText,
            @Bind("createdBy") UUID createdBy);

    record SchemaRow(
            UUID id,
            UUID codesetId,
            Integer version,
            String jsonSchemaText,
            Instant createdAt,
            UUID createdBy) {}
}
