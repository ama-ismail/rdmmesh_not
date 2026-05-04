package bank.rdmmesh.app;

import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.port.IdentityPort;
import bank.rdmmesh.api.port.OwnershipPort;
import bank.rdmmesh.api.security.RdmmeshPrincipal;
import bank.rdmmesh.app.auth.AuthResource;
import bank.rdmmesh.app.health.InfoHealthCheck;
import bank.rdmmesh.catalog.CatalogModule;
import bank.rdmmesh.identity.IdentityModule;
import bank.rdmmesh.identity.JwtAuthenticator;
import bank.rdmmesh.identity.RoleAuthorizer;
import bank.rdmmesh.ownership.OwnershipModule;
import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.auth.oauth.OAuthCredentialAuthFilter;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.jdbi3.JdbiFactory;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;

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
        jdbi.installPlugin(new SqlObjectPlugin());
        log.info("JDBI initialised against {} (SqlObjectPlugin installed)", config.getDatabase().getUrl());

        IdentityPort identityPort = buildIdentityPort(jdbi, config);
        registerJwtAuth(environment, identityPort);

        OwnershipPort ownershipPort = OwnershipModule.buildPort(jdbi);

        CatalogModule.Resources catalog = CatalogModule.build(jdbi, ownershipPort);
        environment.jersey().register(catalog.domains());
        environment.jersey().register(catalog.codeSets());
        environment.jersey().register(catalog.schemas());

        environment.jersey().register(new AuthResource());

        environment.healthChecks().register("info",
                new InfoHealthCheck(getName(), getClass().getPackage().getImplementationVersion()));

        // TODO: register module resources as bounded contexts get filled in.
        //  Authoring (E4), Workflow (E5), Publishing (E6), Distribution (E8), Ownership webhook (E7).
    }

    private static IdentityPort buildIdentityPort(Jdbi jdbi, RdmmeshConfiguration config) {
        var kc = config.getKeycloak();
        var om = config.getOpenmetadata();
        return IdentityModule.builder()
                .jdbi(jdbi)
                .keycloak(new IdentityModule.KeycloakSettings(
                        kc.getIssuerUri(),
                        kc.getJwksUri(),
                        kc.getAudience(),
                        kc.getUsernameClaim(),
                        kc.getGroupsClaim(),
                        kc.getRequiredClaims(),
                        kc.getJwksCacheTtl(),
                        kc.getClockSkew()))
                .openmetadata(new IdentityModule.OpenMetadataSettings(
                        om.getBaseUrl(),
                        om.getBotToken(),
                        om.getConnectTimeout(),
                        om.getRequestTimeout()))
                .build();
    }

    private static void registerJwtAuth(Environment environment, IdentityPort identityPort) {
        var authenticator = new JwtAuthenticator(identityPort);
        var filter = new OAuthCredentialAuthFilter.Builder<RdmmeshPrincipal>()
                .setAuthenticator(authenticator)
                .setAuthorizer(new RoleAuthorizer())
                .setPrefix("Bearer")
                .setRealm("rdmmesh")
                .buildAuthFilter();
        environment.jersey().register(new AuthDynamicFeature(filter));
        environment.jersey().register(new AuthValueFactoryProvider.Binder<>(RdmmeshPrincipal.class));
        environment.jersey().register(RolesAllowedDynamicFeature.class);
        log.info("JWT auth filter registered (Bearer + @RolesAllowed enabled)");
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
