package bank.rdmmesh.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Compile-time enforcement of the bounded-context boundaries described in SPEC §3.3.
 *
 * <p>The rules below are intentionally minimal at bootstrap; as modules get filled in we
 * add more invariants (e.g., distribution does no DB writes — SPEC §3.3 row).
 */
@AnalyzeClasses(
        packages = "bank.rdmmesh",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
public final class ModuleIsolationTest {

    /**
     * Sibling bounded contexts may not import each other directly. The composition root
     * (rdmmesh-app) and the shared API/spec layers are exempt because they are the only
     * legitimate cross-module touch points.
     */
    @ArchTest
    static final ArchRule modules_do_not_depend_on_each_other =
            SlicesRuleDefinition.slices()
                    .matching("bank.rdmmesh.(*)..")
                    .namingSlices("module $1")
                    .should()
                    .notDependOnEachOther()
                    .ignoreDependency(
                            // app may know everyone (it's the wiring root)
                            com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage(
                                    "bank.rdmmesh.app.."),
                            com.tngtech.archunit.base.DescribedPredicate.alwaysTrue())
                    .ignoreDependency(
                            com.tngtech.archunit.base.DescribedPredicate.alwaysTrue(),
                            // depending on api / spec is always allowed
                            com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage(
                                    "bank.rdmmesh.api..", "bank.rdmmesh.spec.."));

    /**
     * The audit module must not depend on a sibling module — it consumes events through
     * the in-process bus interface from {@code rdmmesh-api}. SPEC §3.3 row "audit".
     */
    @ArchTest
    static final ArchRule audit_only_depends_on_api_or_spec =
            classes()
                    .that().resideInAPackage("bank.rdmmesh.audit..")
                    .should().onlyDependOnClassesThat()
                    .resideInAnyPackage(
                            "bank.rdmmesh.audit..",
                            "bank.rdmmesh.api..",
                            "bank.rdmmesh.spec..",
                            "java..", "javax..", "jakarta..",
                            "org.slf4j..", "org.jdbi..",
                            "com.fasterxml..", "io.dropwizard..")
                    // The rule is correct, but the module is empty at bootstrap;
                    // ArchUnit otherwise fails the suite. Removing this once
                    // rdmmesh-audit gets its first class is mandatory.
                    .allowEmptyShould(true);

    /**
     * No bounded context may reach into another's internal sub-packages. Все классы
     * соседнего модуля доступны только через public API (root-package + любое не-internal
     * подмножество). Internal импортирует ТОЛЬКО собственный модуль.
     *
     * <p>Реализация через slice-rule, чтобы за каждый сторонний модуль писать одно правило,
     * а не N×N.
     */
    @ArchTest
    static final ArchRule modules_do_not_import_internals =
            noClasses()
                    .that().resideInAPackage("bank.rdmmesh.(catalog|authoring|workflow|publishing|distribution|identity|ownership|audit|app)..")
                    .and().resideOutsideOfPackages(
                            "bank.rdmmesh.catalog.internal..",
                            "bank.rdmmesh.authoring.internal..",
                            "bank.rdmmesh.workflow.internal..",
                            "bank.rdmmesh.publishing.internal..",
                            "bank.rdmmesh.distribution.internal..",
                            "bank.rdmmesh.identity.internal..",
                            "bank.rdmmesh.ownership.internal..",
                            "bank.rdmmesh.audit.internal..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "bank.rdmmesh.catalog.internal..",
                            "bank.rdmmesh.authoring.internal..",
                            "bank.rdmmesh.workflow.internal..",
                            "bank.rdmmesh.publishing.internal..",
                            "bank.rdmmesh.distribution.internal..",
                            "bank.rdmmesh.identity.internal..",
                            "bank.rdmmesh.ownership.internal..",
                            "bank.rdmmesh.audit.internal..");

    /**
     * SPEC §3.3: модуль {@code distribution} read-only — никаких UPDATE/INSERT/DELETE.
     * Проверяется через запрет JDBI write-аннотаций ({@code @SqlUpdate}/{@code @SqlBatch})
     * — этого достаточно, потому что весь репозиторий ходит в БД через JDBI3 SqlObject.
     * Правило временно с {@code allowEmptyShould(true)}; снять как только в
     * {@code rdmmesh-distribution/src/main/java} появится первый класс.
     */
    @ArchTest
    static final ArchRule distribution_does_no_db_writes =
            noClasses()
                    .that().resideInAPackage("bank.rdmmesh.distribution..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.jdbi.v3.sqlobject.statement.SqlUpdate")
                    .orShould().dependOnClassesThat()
                    .haveFullyQualifiedName("org.jdbi.v3.sqlobject.statement.SqlBatch")
                    .allowEmptyShould(true);
}
