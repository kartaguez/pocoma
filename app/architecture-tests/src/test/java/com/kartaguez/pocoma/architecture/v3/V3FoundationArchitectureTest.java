package com.kartaguez.pocoma.architecture.v3;

import static org.junit.jupiter.api.Assertions.*;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import org.junit.jupiter.api.Test;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.kartaguez.pocoma.architecture.v3.fixture.domain.*;
import com.kartaguez.pocoma.architecture.v3.fixture.engine.*;
import com.kartaguez.pocoma.architecture.v3.fixture.port.*;

/** Reusable direction guards, not certification of the still absent V3 domain/runtime. */
class V3FoundationArchitectureTest {
    private static final String ROOT = "com.kartaguez.pocoma";
    private static final String FIXTURE = ROOT + ".architecture.v3.fixture";
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);

    private static ArchRule pureDomain(String root) {
        return CompositeArchRule.of(classes().that().resideInAPackage(root + ".domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", root + ".domain.."))
                .and(noClasses().that().resideInAPackage(root + ".domain..")
                        .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql.."));
    }

    private static ArchRule applicationDirection(String root) {
        return noClasses().that().resideInAPackage(root + ".engine..")
                .should().dependOnClassesThat().resideInAnyPackage(root + ".infra..", root + ".supra..",
                        root + ".runtime..", "org.springframework..", "jakarta.persistence..");
    }

    // Exact legacy contract exceptions, reviewed for purity. Remove as their V2 consumers migrate.
    private static final java.util.Set<String> LEGACY_PORT_CONTRACTS = java.util.Set.of(
            "write.pot.context.AddPotShareholdersContext", "write.pot.context.CreateExpenseContext",
            "write.pot.context.DeletePotContext", "write.pot.context.UpdatePotDetailsContext",
            "write.pot.context.UpdatePotShareholdersDetailsContext", "write.pot.context.UpdatePotShareholdersWeightsContext",
            "write.pot.context.DeleteExpenseContext", "write.pot.context.UpdateExpenseDetailsContext", "write.pot.context.UpdateExpenseSharesContext",
            "produce.projectiontask.ordering.ProjectionMaterializationOrderingKey",
            "consume.command.model.CommandOutcome", "consume.command.model.BusinessEventsAccepted", "consume.command.discovery.CommandConsumptionCandidate",
            "consume.command.discovery.CommandDiscoveryCursor", "admit.command.model.SubmitRecordedCommandInput", "admit.command.model.SubmittedCommand");

    private static ArchRule portDirection(String root) {
        return classes().that().resideInAnyPackage(root + ".port..", root + ".engine..port..")
                .should().onlyDependOnClassesThat(new DescribedPredicate<JavaClass>("pure port contracts and enumerated V2 value contracts") {
                    public boolean test(JavaClass type) {
                        String pkg = type.getPackageName();
                        if (pkg.startsWith("java.sql") || pkg.startsWith("javax.sql")) return false;
                        if (pkg.startsWith("java.") || (pkg.equals(root + ".domain") || pkg.startsWith(root + ".domain.")) || pkg.startsWith(root + ".contracts.")
                                || pkg.startsWith(root + ".port.") || (pkg.startsWith(root + ".engine.") && (pkg.endsWith(".port") || pkg.contains(".port.")))) return true;
                        return root.equals(ROOT) && LEGACY_PORT_CONTRACTS.stream().anyMatch(contract ->
                                type.getName().equals(ROOT + ".engine." + contract) || type.getName().startsWith(ROOT + ".engine." + contract + "$"));
                    }
                });
    }

    @Test
    void productionImportsAreNonemptyAndExcludeFixtures() {
        for (String family : new String[] {"domain.pot", "domain.useridentity", "domain.consumption", "engine", "port"}) {
            assertTrue(PRODUCTION.stream().anyMatch(type -> type.getPackageName().startsWith(ROOT + "." + family)), family);
        }
        assertTrue(PRODUCTION.stream().anyMatch(type -> type.getPackageName().contains(".engine.") && type.getPackageName().contains(".port.")));
        for (String name : new String[] {"PotContextPort", "PotGlobalVersionPort"})
            assertTrue(PRODUCTION.stream().anyMatch(type -> type.getSimpleName().equals(name)));
        assertFalse(PRODUCTION.stream().anyMatch(type -> type.getPackageName().startsWith(FIXTURE)));
    }

    @Test
    void productionKeepsPureDomainsAndInwardApplicationPorts() {
        pureDomain(ROOT).check(PRODUCTION);
        applicationDirection(ROOT).check(PRODUCTION);
        portDirection(ROOT).check(PRODUCTION);
    }

    @Test
    void positiveFixturesSatisfyTheSameRules() {
        var fixtures = new ClassFileImporter().importClasses(PureDomain.class, PureApplication.class, PurePort.class);
        pureDomain(FIXTURE).check(fixtures);
        applicationDirection(FIXTURE).check(fixtures);
        portDirection(FIXTURE).check(fixtures);
    }

    @Test
    void eachForbiddenDependencyIsActuallyDetected() {
        assertViolation(pureDomain(FIXTURE), ForbiddenDomain.class);
        assertViolation(pureDomain(FIXTURE), ForbiddenSqlDomain.class);
        assertViolation(applicationDirection(FIXTURE), ForbiddenApplication.class);
        assertViolation(portDirection(FIXTURE), ForbiddenPort.class);
    }

    @Test
    void nestedPortsCannotEscapeTheSelectorOrLeakImplementations() {
        String pkg = FIXTURE + ".engine.sample.port.persistence.";
        var importer = new ClassFileImporter();
        var positive = importer.importPackages(pkg.substring(0,pkg.length()-1));
        assertEquals(9, positive.size());
        var good = positive.get(pkg + "GoodNestedPort");
        portDirection(FIXTURE).check(importer.importClasses(load(good.getName())));
        for (String name : new String[] {"BadApplicationPort", "BadInfrastructurePort", "BadSpringPort", "BadJpaPort", "BadSqlPort", "BadJdbcPort", "BadAnnotationPort", "BadInheritancePort"}) {
            Class<?> fixture = load(pkg + name);
            assertViolation(portDirection(FIXTURE), fixture);
            // The old top-level selector would see no port here: this assertion makes coverage explicit.
            assertFalse(fixture.getPackageName().startsWith(FIXTURE + ".port."));
        }
    }

    private static Class<?> load(String name) {
        try { return Class.forName(name); } catch (ClassNotFoundException error) { throw new AssertionError(error); }
    }

    private static void assertViolation(ArchRule rule, Class<?> fixture) {
        var result = rule.evaluate(new ClassFileImporter().importClasses(fixture));
        assertTrue(result.hasViolation());
        assertTrue(result.getFailureReport().toString().contains(fixture.getName()));
    }
}
