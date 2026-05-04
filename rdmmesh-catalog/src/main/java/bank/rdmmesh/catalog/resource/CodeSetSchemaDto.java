package bank.rdmmesh.catalog.resource;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * REST-DTO для CodeSetSchema. Используем собственный record вместо
 * {@code bank.rdmmesh.spec.entity.CodeSetSchema} — поле {@code json_schema} там
 * сгенерировано jsonschema2pojo как закрытый POJO, который не принимает произвольные
 * fields ("type"/"properties"/...), и десериализация падает на любом реальном JSON
 * Schema'е.
 *
 * <p>Эта структура зеркалирует JSON Schema entity'и (см. {@code rdmmesh-spec/schema/entity/code-set-schema.json}),
 * но {@code json_schema} даём как свободный {@link Map}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CodeSetSchemaDto(
        @JsonProperty("id") String id,
        @JsonProperty("codeset_id") String codesetId,
        @JsonProperty("version") Integer version,
        @JsonProperty("json_schema") Map<String, Object> jsonSchema,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("created_by") String createdBy) {}
