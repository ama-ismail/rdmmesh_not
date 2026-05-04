package bank.rdmmesh.identity;

import java.security.Principal;
import java.util.Set;
import java.util.UUID;

import bank.rdmmesh.api.port.IdentityPort.AuthenticatedUser;

/**
 * Adapter {@link AuthenticatedUser} → {@link Principal} для интеграции с Dropwizard-auth /
 * Jersey. {@link IdentityPort#AuthenticatedUser} живёт в {@code rdmmesh-api} и сознательно
 * не зависит от {@link Principal}, чтобы оставаться технологически нейтральным.
 *
 * <p>Имя principal'а — {@code username} (sAMAccountName). Ролевая семантика — через
 * {@link #hasRole(String)}, что используется в {@code Authorizer<RdmmeshPrincipal>}.
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
