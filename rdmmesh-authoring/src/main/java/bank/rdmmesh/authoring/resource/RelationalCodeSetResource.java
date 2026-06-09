package bank.rdmmesh.authoring.resource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import bank.rdmmesh.api.security.RdmmeshPrincipal;
import bank.rdmmesh.authoring.internal.relational.RelationalStoreService;
import bank.rdmmesh.authoring.internal.relational.RelationalStoreService.ProvisionResult;
import bank.rdmmesh.authoring.internal.relational.RelationalStoreService.SyncResult;

import io.dropwizard.auth.Auth;

/**
 * REST для relational store (спайк полной замены JSONB): материализация CodeSet'а в
 * реальную типизированную таблицу схемы {@code rd_data} и запись/чтение строк.
 *
 * <ul>
 *   <li>{@code POST /relational/codesets/{id}/provision} — CREATE TABLE по key_spec+схеме</li>
 *   <li>{@code POST /relational/codesets/{id}/rows}      — upsert строки (JSON-объект колонка→значение)</li>
 *   <li>{@code GET  /relational/codesets/{id}/rows}      — все строки физической таблицы</li>
 * </ul>
 *
 * <p>Префикс {@code /relational} (а не {@code /codesets}) — чтобы не пересекаться с
 * catalog'овым root-resource'ом {@code CodeSetResource} в Jersey-роутинге.
 */
@Path("/relational/codesets/{id}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public final class RelationalCodeSetResource {

    private final RelationalStoreService store;

    public RelationalCodeSetResource(RelationalStoreService store) {
        this.store = store;
    }

    @POST
    @Path("/provision")
    @RolesAllowed({"RDM_AUTHOR", "RDM_SCHEMA_DESIGNER", "RDM_ADMIN"})
    public ProvisionResult provision(@Auth RdmmeshPrincipal principal, @PathParam("id") String id) {
        try {
            return store.provision(parseUuid(id));
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    @POST
    @Path("/rows")
    @RolesAllowed({"RDM_AUTHOR", "RDM_SCHEMA_DESIGNER", "RDM_ADMIN"})
    public Response upsertRow(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @NotNull Map<String, Object> row) {
        try {
            store.upsertRow(parseUuid(id), row);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        }
    }

    @POST
    @Path("/sync")
    @RolesAllowed({"RDM_AUTHOR", "RDM_SCHEMA_DESIGNER", "RDM_ADMIN"})
    public SyncResult sync(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @QueryParam("version_id") String versionId) {
        UUID codesetId = parseUuid(id);
        if (versionId == null || versionId.isBlank()) {
            throw new WebApplicationException("version_id query param is required",
                    Response.Status.BAD_REQUEST);
        }
        try {
            SyncResult result = store.syncFromVersion(parseUuid(versionId));
            if (!result.codesetId().equals(codesetId)) {
                throw new WebApplicationException(
                        "version " + versionId + " does not belong to codeset " + id,
                        Response.Status.BAD_REQUEST);
            }
            return result;
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    @GET
    @Path("/rows")
    public List<Map<String, Object>> listRows(
            @Auth RdmmeshPrincipal principal, @PathParam("id") String id) {
        try {
            return store.listRows(parseUuid(id));
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        }
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            throw new WebApplicationException("id must be a UUID, got '" + s + "'",
                    Response.Status.BAD_REQUEST);
        }
    }
}
