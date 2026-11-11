package com.kartaguez.pocoma.architecture.v3;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

import static org.junit.jupiter.api.Assertions.*;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

/** Additive I2 guards; no existing architecture oracle is removed or filtered. */
class V3AdmissionArchitectureTest {
    static final String ROOT = "com.kartaguez.pocoma";
    static final com.tngtech.archunit.core.domain.JavaClasses CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages(ROOT);

    @Test
    void admissionHasNoBusinessAuthorityOrFramework() {
        noClasses()
                .that()
                .resideInAnyPackage(
                        ROOT + ".engine.admit.command.v3..",
                        ROOT + ".engine.admit.identity.v3..")
                .should()
                .dependOnClassesThat(
                        new DescribedPredicate<JavaClass>("forbidden infrastructure or frameworks") {
                            public boolean test(JavaClass type) {
                                if (type.getName().equals("java.sql.Timestamp")) {
                                    return false;
                                }
                                String pkg = type.getPackageName();
                                return pkg.startsWith(ROOT + ".infra.")
                                        || pkg.equals(ROOT + ".infra")
                                        || pkg.startsWith(ROOT + ".runtime.")
                                        || pkg.equals(ROOT + ".runtime")
                                        || pkg.startsWith(ROOT + ".supra.")
                                        || pkg.equals(ROOT + ".supra")
                                        || pkg.startsWith(ROOT + ".engine.read.")
                                        || pkg.equals(ROOT + ".engine.read")
                                        || pkg.startsWith("org.springframework.")
                                        || pkg.equals("org.springframework")
                                        || pkg.startsWith("java.sql.")
                                        || pkg.equals("java.sql")
                                        || pkg.startsWith("javax.sql.")
                                        || pkg.equals("javax.sql");
                            }
                        })
                .check(CLASSES);
        for (var c : CLASSES)
            if (c.getPackageName().startsWith(ROOT + ".engine.admit.")
                    && c.getPackageName().contains(".v3"))
                assertTrue(
                        c.getDirectDependenciesFromSelf().stream()
                                .noneMatch(
                                        d ->
                                                Set.of(
                                                                "PotActorAuthority",
                                                                "PotHistoryPort",
                                                                "IdentityAuthority",
                                                                "JdbcTemplate")
                                                        .contains(
                                                                d.getTargetClass()
                                                                        .getSimpleName())),
                        c.getName());
    }

    @Test
    void dedicatedWriterUsesExistingPhysicalOwner() {
        var writer =
                CLASSES.stream()
                        .filter(c -> c.getSimpleName().equals("JdbcAdmissionStore"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                writer.getDirectDependenciesFromSelf().stream()
                        .anyMatch(
                                d ->
                                        d.getTargetClass()
                                                .getSimpleName()
                                                .equals("SpringAttemptTransactions")));
        assertTrue(
                writer.getMethods().stream()
                        .noneMatch(
                                m ->
                                        m.isAnnotatedWith(
                                                "org.springframework.transaction.annotation.Transactional")));
        assertTrue(
                writer.getDirectDependenciesFromSelf().stream()
                        .noneMatch(
                                d ->
                                        d.getTargetClass().getSimpleName().contains("History")
                                                || d.getTargetClass()
                                                        .getSimpleName()
                                                        .contains("ActorAuthority")));
    }

    @Test
    void httpAssemblyRemainsExplicitAndLinkPhysicalObjectsStayAbsent() throws Exception {
        String base =
                "../composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/";
        String config = Files.readString(Path.of(base + "V3AdmissionHttpConfiguration.java"));
        assertTrue(config.contains("@Profile(V3AdmissionHttpConfiguration.PROFILE)"));
        assertTrue(config.contains("AdmissionSqlAuthority.verify"));
        String sql =
                Files.readString(
                        Path.of(
                                "../infra-persistence-primary-jpa/src/main/resources/db/v3-admission/migration/V7__public_admission.sql"));
        assertTrue(sql.contains("deferrable initially deferred"));
        assertFalse(sql.contains("grant_fingerprint"));
        assertFalse(sql.contains("Consent"));
        assertFalse(sql.contains("public_origin"));
        var controller=CLASSES.stream().filter(c->c.getSimpleName().equals("V3AdmissionController")).findFirst().orElseThrow();
        assertTrue(controller.isAnnotatedWith("org.springframework.context.annotation.Profile"));
        String transport =
                Files.readString(
                        Path.of(
                                "../supra-web-write/src/main/java/com/kartaguez/pocoma/supra/http/write/v3/V3AdmissionTransport.java"));
        assertFalse(transport.contains("supra.web.read"));
    }
}
