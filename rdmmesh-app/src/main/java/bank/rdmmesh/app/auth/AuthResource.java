package bank.rdmmesh.app.auth;

import java.util.Set;
import java.util.UUID;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import bank.rdmmesh.identity.RdmmeshPrincipal;
import io.dropwizard.auth.Auth;

/**
 * Маленький protected endpoint для smoke-теста JWT-флоу. Возвращает identity текущего
 * пользователя, без обращения к БД и без побочных эффектов. Должен быть доступен любому
 * аутентифицированному пользователю — до этапа E5 (Workflow) проверять конкретные роли
 * нет смысла.
 */
@Path("/auth/me")
@Produces(MediaType.APPLICATION_JSON)
public final class AuthResource {

    @GET
    public Me me(@Auth RdmmeshPrincipal principal) {
        return new Me(
                principal.omUserId(),
                principal.keycloakSub(),
                principal.getName(),
                principal.baseRoles());
    }

    public record Me(
            UUID omUserId,
            UUID keycloakSub,
            String username,
            Set<String> baseRoles) {}
}
