package bank.rdmmesh.catalog.resource;

import java.util.List;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.annotation.JsonProperty;

import bank.rdmmesh.api.security.RdmmeshPrincipal;
import bank.rdmmesh.catalog.internal.mapper.CatalogMappers;
import bank.rdmmesh.catalog.internal.service.CatalogService;
import bank.rdmmesh.spec.entity.Domain;
import io.dropwizard.auth.Auth;

/**
 * REST для catalog'а — domain endpoints (SPEC §3.5). Любой аутентифицированный пользователь
 * может читать; создание / patch — только {@code RDM_ADMIN} (domain'ы — отражение OM, в
 * проде создаются ingestion'ом, в dev/pilot — админом вручную).
 */
@Path("/domains")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public final class DomainResource {

    private final CatalogService catalog;

    public DomainResource(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GET
    public List<Domain> list(@Auth RdmmeshPrincipal principal) {
        return catalog.listDomains();
    }

    @GET
    @Path("/{id}")
    public Domain get(@Auth RdmmeshPrincipal principal, @PathParam("id") String id) {
        UUID uid = CatalogMappers.parseUuid(id, "id");
        return catalog.findDomain(uid)
                .orElseThrow(() -> new NotFoundException("domain " + id));
    }

    @POST
    @RolesAllowed("RDM_ADMIN")
    public Response create(@Auth RdmmeshPrincipal principal, @Valid @NotNull NewDomainRequest req) {
        UUID omId = CatalogMappers.parseUuid(req.omDomainId, "om_domain_id");
        try {
            Domain created = catalog.createDomain(new CatalogService.NewDomain(
                    omId,
                    req.name,
                    req.displayName,
                    req.description,
                    req.labelRu,
                    req.labelEn,
                    req.tags));
            return Response.status(Response.Status.CREATED).entity(created).build();
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        }
    }

    @PATCH
    @Path("/{id}")
    @RolesAllowed("RDM_ADMIN")
    public Domain patch(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @Valid @NotNull DomainPatchRequest req) {
        UUID uid = CatalogMappers.parseUuid(id, "id");
        return catalog.patchDomain(
                        uid,
                        new CatalogService.DomainPatch(
                                req.displayName,
                                req.description,
                                req.labelRu,
                                req.labelEn,
                                req.tags))
                .orElseThrow(() -> new NotFoundException("domain " + id));
    }

    /** POST-DTO. Public fields — Jersey/Jackson их подхватит, мы тут только проверяем. */
    public static final class NewDomainRequest {
        @JsonProperty("om_domain_id")
        @NotEmpty
        public String omDomainId;

        @JsonProperty("name")
        @NotEmpty
        @Pattern(regexp = "^[a-z][a-z0-9_]{0,63}$",
                message = "name must be lower snake_case, ≤64 chars")
        public String name;

        @JsonProperty("display_name")
        public String displayName;

        @JsonProperty("description")
        public String description;

        @JsonProperty("label_ru")
        public String labelRu;

        @JsonProperty("label_en")
        public String labelEn;

        @JsonProperty("tags")
        public String[] tags;
    }

    public static final class DomainPatchRequest {
        @JsonProperty("display_name")
        public String displayName;

        @JsonProperty("description")
        public String description;

        @JsonProperty("label_ru")
        public String labelRu;

        @JsonProperty("label_en")
        public String labelEn;

        @JsonProperty("tags")
        public String[] tags;
    }
}
