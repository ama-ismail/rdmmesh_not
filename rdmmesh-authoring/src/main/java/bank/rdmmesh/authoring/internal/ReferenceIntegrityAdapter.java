package bank.rdmmesh.authoring.internal;

import java.util.List;
import java.util.UUID;

import bank.rdmmesh.api.port.ReferenceIntegrityPort;
import bank.rdmmesh.authoring.internal.relational.RelationalStoreService;

/**
 * Реализация {@link ReferenceIntegrityPort} поверх relational store (Stage 7).
 * Делегирует в {@link RelationalStoreService#versionReferenceViolations(UUID)}.
 */
public final class ReferenceIntegrityAdapter implements ReferenceIntegrityPort {

    private final RelationalStoreService relational;

    public ReferenceIntegrityAdapter(RelationalStoreService relational) {
        this.relational = relational;
    }

    @Override
    public List<String> violations(UUID versionId) {
        return relational.versionReferenceViolations(versionId);
    }
}
