package bank.rdmmesh.identity;

import jakarta.ws.rs.container.ContainerRequestContext;

import bank.rdmmesh.api.security.RdmmeshPrincipal;
import io.dropwizard.auth.Authorizer;

/**
 * Authorizer для базовых функциональных ролей RDM (см. SPEC §2.1). Asset-level роли
 * (Owner/Steward/Expert per CodeSet) приходят из OpenMetadata и проверяются отдельной
 * политикой через {@code OwnershipPort}.
 */
public final class RoleAuthorizer implements Authorizer<RdmmeshPrincipal> {

    @Override
    public boolean authorize(
            RdmmeshPrincipal principal, String role, ContainerRequestContext requestContext) {
        return principal != null && principal.hasRole(role);
    }
}
