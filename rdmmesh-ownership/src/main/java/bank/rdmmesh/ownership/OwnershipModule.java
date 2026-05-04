package bank.rdmmesh.ownership;

import org.jdbi.v3.core.Jdbi;

import bank.rdmmesh.api.port.OwnershipPort;
import bank.rdmmesh.ownership.internal.PostgresOwnershipPort;

/**
 * Composition factory для модуля {@code rdmmesh-ownership}. До эпика E7 экспортирует только
 * {@link OwnershipPort}; webhook-receiver и rate-limiter добавятся туда же позже.
 */
public final class OwnershipModule {

    private OwnershipModule() {}

    public static OwnershipPort buildPort(Jdbi jdbi) {
        return new PostgresOwnershipPort(jdbi);
    }
}
