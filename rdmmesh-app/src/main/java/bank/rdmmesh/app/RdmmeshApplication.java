package bank.rdmmesh.app;

import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.jdbi3.JdbiFactory;

import bank.rdmmesh.app.health.InfoHealthCheck;

/**
 * rdmmesh composition root. The only place that knows about all bounded contexts;
 * each module exposes its public API via interfaces from {@code rdmmesh-api}.
 *
 * <p>MVP wiring (resources, services, ports) lands here as modules get filled in.
 */
public final class RdmmeshApplication extends Application<RdmmeshConfiguration> {

    private static final Logger log = LoggerFactory.getLogger(RdmmeshApplication.class);

    public static void main(String[] args) throws Exception {
        new RdmmeshApplication().run(args);
    }

    @Override
    public String getName() {
        return "rdmmesh";
    }

    @Override
    public void initialize(Bootstrap<RdmmeshConfiguration> bootstrap) {
        bootstrap.setConfigurationSourceProvider(new SubstitutingSourceProvider(
                bootstrap.getConfigurationSourceProvider(),
                new EnvironmentVariableSubstitutor(/* strict */ false)));
    }

    @Override
    public void run(RdmmeshConfiguration config, Environment environment) {
        if (config.getFlyway().isAutoMigrate()) {
            runFlyway(config);
        } else {
            log.info("Flyway autoMigrate is disabled — skipping migrations.");
        }

        Jdbi jdbi = new JdbiFactory().build(environment, config.getDatabase(), "rdmmesh");
        log.info("JDBI initialised against {}", config.getDatabase().getUrl());

        environment.healthChecks().register("info",
                new InfoHealthCheck(getName(), getClass().getPackage().getImplementationVersion()));

        // TODO: register module resources as bounded contexts get filled in.
        // jdbi will be passed into module-level wiring builders here.
    }

    private static void runFlyway(RdmmeshConfiguration config) {
        var db = config.getDatabase();
        var fw = config.getFlyway();

        log.info("Running Flyway against {} (default schema {}, schemas {})",
                db.getUrl(), fw.getDefaultSchema(), fw.getSchemas());

        Flyway flyway = Flyway.configure()
                .dataSource(db.getUrl(), db.getUser(), db.getPassword())
                .locations(fw.getLocations().toArray(String[]::new))
                .schemas(fw.getSchemas().toArray(String[]::new))
                .defaultSchema(fw.getDefaultSchema())
                .createSchemas(true)
                .baselineOnMigrate(true)
                .load();

        var result = flyway.migrate();
        log.info("Flyway: {} migrations applied (initialSchemaVersion={}, targetSchemaVersion={})",
                result.migrationsExecuted, result.initialSchemaVersion, result.targetSchemaVersion);
    }
}
