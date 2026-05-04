package bank.rdmmesh.api.security;

import java.security.Principal;
import java.util.Set;
import java.util.UUID;

import bank.rdmmesh.api.port.IdentityPort.AuthenticatedUser;

/**
 * Адаптер {@link AuthenticatedUser} → {@link Principal}. Лежит в {@code rdmmesh-api},
 * чтобы любой bounded context (catalog, authoring, …) мог принимать его через
 * {@code @Auth RdmmeshPrincipal} в JAX-RS-resource'ах, не импортируя соседние модули
 * (запрещено ArchUnit'ом, см. {@code ModuleIsolationTest}).
 *
 * <p>Реализация {@link Principal#getName()} возвращает {@code username} (sAMAccountName)
 * — это то, что Jersey показывает в access-log'е и Dropwizard передаёт в {@code AuthFilter}.
 * Asset-level роли (Owner/Steward/Expert per CodeSet) живут в {@code OwnershipPort} и
 * проверяются отдельной политикой.
 */
public final class RdmmeshPrincipal implements Principal {

    private final AuthenticatedUser user;

    public RdmmeshPrincipal(AuthenticatedUser user) {
        this.user = user;
    }

    public AuthenticatedUser user() {
        return user;
    }

    public UUID omUserId() {
        return user.omUserId();
    }

    public UUID keycloakSub() {
        return user.keycloakSub();
    }

    public Set<String> baseRoles() {
        return user.baseRoles();
    }

    public boolean hasRole(String role) {
        return user.baseRoles().contains(role);
    }

    @Override
    public String getName() {
        return user.username();
    }

    @Override
    public String toString() {
        return "RdmmeshPrincipal[" + user.username() + ", " + user.baseRoles() + "]";
    }
}
