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
 * DAO для {@code catalog.domain}. В нашей модели Domain — отражение domain'а из
 * OpenMetadata (см. SPEC §2.4); таблица — кэш для join'ов и referential integrity
 * на {@code code_set.domain_id}. Identity flows from OM (UUID — {@code om_domain_id}).
 */
public interface DomainDao {

    @SqlQuery(
            "SELECT id, om_domain_id, name, display_name, description,"
                    + " label_ru, label_en, tags, created_at, updated_at"
                    + " FROM catalog.domain ORDER BY name")
    @RegisterConstructorMapper(DomainRow.class)
    List<DomainRow> findAll();

    @SqlQuery(
            "SELECT id, om_domain_id, name, display_name, description,"
                    + " label_ru, label_en, tags, created_at, updated_at"
                    + " FROM catalog.domain WHERE id = :id")
    @RegisterConstructorMapper(DomainRow.class)
    Optional<DomainRow> findById(@Bind("id") UUID id);

    @SqlQuery(
            "SELECT id, om_domain_id, name, display_name, description,"
                    + " label_ru, label_en, tags, created_at, updated_at"
                    + " FROM catalog.domain WHERE name = :name")
    @RegisterConstructorMapper(DomainRow.class)
    Optional<DomainRow> findByName(@Bind("name") String name);

    @SqlQuery(
            "SELECT id, om_domain_id, name, display_name, description,"
                    + " label_ru, label_en, tags, created_at, updated_at"
                    + " FROM catalog.domain WHERE om_domain_id = :omDomainId")
    @RegisterConstructorMapper(DomainRow.class)
    Optional<DomainRow> findByOmId(@Bind("omDomainId") UUID omDomainId);

    @SqlUpdate(
            """
            INSERT INTO catalog.domain (id, om_domain_id, name, display_name, description,
                                        label_ru, label_en, tags)
            VALUES (:id, :omDomainId, :name, :displayName, :description,
                    :labelRu, :labelEn, :tags)
            """)
    int insert(
            @Bind("id") UUID id,
            @Bind("omDomainId") UUID omDomainId,
            @Bind("name") String name,
            @Bind("displayName") String displayName,
            @Bind("description") String description,
            @Bind("labelRu") String labelRu,
            @Bind("labelEn") String labelEn,
            @Bind("tags") String[] tags);

    @SqlUpdate(
            """
            UPDATE catalog.domain
               SET display_name = COALESCE(:displayName, display_name),
                   description  = COALESCE(:description, description),
                   label_ru     = COALESCE(:labelRu,     label_ru),
                   label_en     = COALESCE(:labelEn,     label_en),
                   tags         = COALESCE(:tags,        tags),
                   updated_at   = now()
             WHERE id = :id
            """)
    int patch(
            @Bind("id") UUID id,
            @Bind("displayName") String displayName,
            @Bind("description") String description,
            @Bind("labelRu") String labelRu,
            @Bind("labelEn") String labelEn,
            @Bind("tags") String[] tags);

    /** Snapshot строки domain'а — внутренний транспорт между DAO и mapper'ом. */
    record DomainRow(
            UUID id,
            UUID omDomainId,
            String name,
            String displayName,
            String description,
            String labelRu,
            String labelEn,
            String[] tags,
            Instant createdAt,
            Instant updatedAt) {}
}
