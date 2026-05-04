package bank.rdmmesh.api.port;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Validates Keycloak JWTs, materialises the OM ↔ Keycloak identity mapping, and exposes
 * the base functional roles carried in the AD groups claim. Asset-level roles do NOT
 * come from this port — they live in {@link OwnershipPort}.
 */
public interface IdentityPort {

    /** Resolved identity attached to an authenticated request. */
    record AuthenticatedUser(
            UUID omUserId,
            UUID keycloakSub,
            String username,
            Set<String> baseRoles) {}

    /**
     * Validate a JWT and resolve the caller. Implementations MUST verify the signature
     * against a cached JWKS (Keycloak), check exp / nbf / iss / aud, then map sub →
     * om_user_id (lazy lookup against OM REST on cache miss).
     */
    AuthenticatedUser authenticate(String bearerToken);

    /** Lookup-only path used by background workers / webhook handlers, no JWT. */
    Optional<UUID> resolveOmUserId(UUID keycloakSub);

    /** Reverse path used by the OM webhook receiver (OM gives us om_user_id). */
    Optional<UUID> resolveKeycloakSub(UUID omUserId);
}
