package com.kartaguez.pocoma.architecture.v3;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

import static org.junit.jupiter.api.Assertions.*;

import com.tngtech.archunit.core.importer.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

/**
 * I2 only: physical primitives exist without an active participant, composition or Pot authority.
 */
class V3IdentityAuthorityArchitectureTest {
    static final String ROOT = "com.kartaguez.pocoma";
    static final com.tngtech.archunit.core.domain.JavaClasses CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages(ROOT);

    @Test
    void l3_identity_authority_has_no_owner_or_runtime() {
        var authority =
                CLASSES.stream()
                        .filter(c -> c.getSimpleName().equals("JdbcIdentityAuthority"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                authority.getAllRawInterfaces().stream()
                        .anyMatch(c -> c.getSimpleName().equals("IdentityAuthority")));
        assertTrue(
                authority.getMethodCallsFromSelf().stream()
                        .anyMatch(
                                c ->
                                        c.getTargetOwner()
                                                        .getSimpleName()
                                                        .equals("SpringAttemptTransactions")
                                                && c.getTarget().getName().equals("guarded")));
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".infra.persistence.primary.jpa.v3.identity..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.transaction.annotation..",
                        "org.springframework.transaction.support.TransactionTemplate",
                        "org.springframework.orm.jpa..",
                        "jakarta.persistence..",
                        ROOT + ".runtime..",
                        ROOT + ".infra.persistence.read..",
                        ROOT + ".domain.pot..")
                .check(CLASSES);
        for (var c :
                CLASSES.stream()
                        .filter(
                                c ->
                                        c.getPackageName()
                                                .startsWith(
                                                        ROOT
                                                                + ".infra.persistence.primary.jpa.v3.identity"))
                        .toList()) {
            assertFalse(
                    c.getMethodCallsFromSelf().stream()
                            .anyMatch(
                                    call ->
                                            Set.of(
                                                                    "commit",
                                                                    "rollback",
                                                                    "getConnection",
                                                                    "setSavepoint",
                                                                    "bindResource",
                                                                    "registerSynchronization",
                                                                    "write",
                                                                    "read",
                                                                    "scheduleAtFixedRate")
                                                            .contains(call.getTarget().getName())
                                                    && !call.getTargetOwner()
                                                            .getName()
                                                            .equals(c.getName())),
                    c.getName());
        }
    }

    @Test
    void l3_canonical_acquisition_is_the_only_eui_writer_path() throws Exception {
        var root = Path.of("../infra-persistence-primary-jpa/src/main/java");
        try (var files = Files.walk(root)) {
            for (var path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = Files.readString(path);
                if (code.contains("insert into v3_eui_authority")
                        || code.contains("update v3_eui_authority"))
                    assertEquals("JdbcIdentityAuthority.java", path.getFileName().toString());
            }
        }
        var code =
                Files.readString(
                        root.resolve(
                                "com/kartaguez/pocoma/infra/persistence/primary/jpa/v3/identity/JdbcIdentityAuthority.java"));
        assertTrue(code.contains("ExternalUserIdentity::compareKeys"));
        assertTrue(code.contains("on conflict (issuer,subject) do nothing"));
        assertFalse(code.contains("pg_advisory"));
        assertFalse(code.contains("REQUIRES_NEW"));
        var generator =
                CLASSES.stream()
                        .filter(c -> c.getSimpleName().equals("SecureBindingTokenGenerator"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                generator.getMethodCallsFromSelf().stream()
                        .anyMatch(
                                c ->
                                        c.getTargetOwner()
                                                        .getName()
                                                        .equals("java.security.SecureRandom")
                                                && c.getTarget().getName().equals("nextBytes")));
        assertFalse(
                generator.getDirectDependenciesFromSelf().stream()
                        .anyMatch(d -> d.getTargetClass().getName().equals("java.util.UUID")));
    }
}
