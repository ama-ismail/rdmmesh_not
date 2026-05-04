package bank.rdmmesh.app.health;

import com.codahale.metrics.health.HealthCheck;

/** Liveness probe — always healthy, returns the running version for ops dashboards. */
public final class InfoHealthCheck extends HealthCheck {

    private final String appName;
    private final String version;

    public InfoHealthCheck(String appName, String version) {
        this.appName = appName;
        this.version = version != null ? version : "dev";
    }

    @Override
    protected Result check() {
        return Result.builder().healthy().withMessage("%s %s".formatted(appName, version)).build();
    }
}
