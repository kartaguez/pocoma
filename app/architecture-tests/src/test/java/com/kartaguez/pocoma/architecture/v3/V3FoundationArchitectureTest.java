package com.kartaguez.pocoma.architecture.v3;

import static org.junit.jupiter.api.Assertions.*;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import org.junit.jupiter.api.Test;
import com.tngtech.archunit.core.domain.JavaClasses;
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

    private static ArchRule portDirection(String root) {
        return classes().that().resideInAPackage(root + ".port..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", root + ".port..",
                        root + ".domain..", root + ".contracts..");
    }

    @Test
    void productionImportsAreNonemptyAndExcludeFixtures() {
        for (String family : new String[] {"domain.pot", "domain.useridentity", "domain.consumption", "engine", "port"}) {
            assertTrue(PRODUCTION.stream().anyMatch(type -> type.getPackageName().startsWith(ROOT + "." + family)), family);
        }
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

    private static void assertViolation(ArchRule rule, Class<?> fixture) {
        var result = rule.evaluate(new ClassFileImporter().importClasses(fixture));
        assertTrue(result.hasViolation());
        assertTrue(result.getFailureReport().toString().contains(fixture.getName()));
    }
}
