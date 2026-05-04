package bank.rdmmesh.api.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import bank.rdmmesh.spec.entity.AssetOwnership;
import bank.rdmmesh.spec.events.WorkflowTransitionEvent;

/**
 * Drives state-machine transitions on a {@code CodeSetVersion}. Owns the 4-eyes invariants:
 * legal transitions per current status, role gate (steward / owner from {@link OwnershipPort}),
 * and self-approval prevention (created_by ≠ reviewed_by ≠ approved_by). See SPEC §2.2 etap 3.
 *
 * <p>Implementations must never let an admin bypass the state machine (SPEC §3.2 #7).
 */
public interface WorkflowPort {

    /**
     * Apply a transition. Returns the persisted event so callers can publish it on the
     * in-process event bus and build audit entries from a single source of truth.
     *
     * @throws IllegalStateTransitionException when the current → target transition is
     *         not allowed by the state machine.
     * @throws SelfApprovalException when the actor is also the creator/previous reviewer.
     * @throws InsufficientRoleException when the actor lacks the required role for the
     *         target transition (resolved via {@link OwnershipPort#rolesOf(UUID, UUID)}).
     */
    WorkflowTransitionEvent transition(
            UUID versionId,
            String targetStatus,
            UUID actor,
            String comment);

    List<WorkflowTransitionEvent> history(UUID versionId);

    Optional<AssetOwnership> openTaskFor(UUID versionId, String requiredRole);

    /** Thrown when a target status is unreachable from the current one. */
    class IllegalStateTransitionException extends RuntimeException {
        public IllegalStateTransitionException(String message) {
            super(message);
        }
    }

    /** Thrown when the same OM user would act in two of {created_by, reviewed_by, approved_by}. */
    class SelfApprovalException extends RuntimeException {
        public SelfApprovalException(String message) {
            super(message);
        }
    }

    /** Thrown when the actor's resolved roles do not include the required role. */
    class InsufficientRoleException extends RuntimeException {
        public InsufficientRoleException(String message) {
            super(message);
        }
    }
}
