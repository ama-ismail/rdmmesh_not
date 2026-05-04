package bank.rdmmesh.catalog;

import org.jdbi.v3.core.Jdbi;

import bank.rdmmesh.api.port.OwnershipPort;
import bank.rdmmesh.catalog.internal.service.CatalogService;
import bank.rdmmesh.catalog.resource.CodeSetResource;
import bank.rdmmesh.catalog.resource.CodeSetSchemaResource;
import bank.rdmmesh.catalog.resource.DomainResource;

/**
 * Composition factory модуля {@code rdmmesh-catalog}. Возвращает контейнер с уже
 * сконфигурированными resource'ами, чтобы {@code RdmmeshApplication} мог
 * зарегистрировать их одним блоком.
 */
public final class CatalogModule {

    private CatalogModule() {}

    public static Resources build(Jdbi jdbi, OwnershipPort ownershipPort) {
        CatalogService service = new CatalogService(jdbi, ownershipPort);
        return new Resources(
                new DomainResource(service),
                new CodeSetResource(service),
                new CodeSetSchemaResource(service));
    }

    public record Resources(
            DomainResource domains,
            CodeSetResource codeSets,
            CodeSetSchemaResource schemas) {}
}
