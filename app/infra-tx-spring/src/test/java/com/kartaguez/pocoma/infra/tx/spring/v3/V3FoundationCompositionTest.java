package com.kartaguez.pocoma.infra.tx.spring.v3;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunner;
import com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunnerConfiguration;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;

class V3FoundationCompositionTest extends PostgresFoundationTestSupport {
    @Test
    void existingConfigurationInjectsTheActualManagerWithoutApplicationBootstrap() {
        new ApplicationContextRunner().withUserConfiguration(SpringTransactionRunnerConfiguration.class)
                .withBean("writeManager", JpaTransactionManager.class, () -> write.manager)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var runner = context.getBean(TransactionRunner.class);
                    runner.runInTransaction(() -> {
                        write.jdbc.update("insert into foundation_effect values (?,?)", UUID.randomUUID(), "wired");
                        assertEquals(0, write.observedEffects());
                        return null;
                    });
                    assertEquals(1, write.observedEffects());
                    assertEquals(0, read.observedEffects());
                    assertEquals(0, context.getBeansOfType(org.flywaydb.core.Flyway.class).size());
                    assertFalse(java.util.Arrays.stream(context.getBeanDefinitionNames())
                            .anyMatch(name -> name.toLowerCase().contains("worker") || name.equals("readStoreMigrations")));
                });
    }

    @Test
    void ambiguousManagersAreRefusedInsteadOfSilentlyChoosingAnAuthority() {
        new ApplicationContextRunner().withUserConfiguration(SpringTransactionRunnerConfiguration.class)
                .withBean("writeManager", JpaTransactionManager.class, () -> write.manager)
                .withBean("readManager", JpaTransactionManager.class, () -> read.manager)
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    Throwable root = context.getStartupFailure();
                    while (root.getCause() != null) root = root.getCause();
                    assertInstanceOf(org.springframework.beans.factory.NoUniqueBeanDefinitionException.class, root);
                });
    }

    @Test
    void explicitRealmCompositionUsesIndependentPhysicalTransactions() {
        new ApplicationContextRunner().withUserConfiguration(ExplicitRealms.class)
                .withBean("writeManager", JpaTransactionManager.class, () -> write.manager)
                .withBean("readManager", JpaTransactionManager.class, () -> read.manager)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var writeRunner = context.getBean("writeRunner", TransactionRunner.class);
                    var readRunner = context.getBean("readRunner", TransactionRunner.class);
                    assertThrows(IllegalStateException.class, () -> writeRunner.runInTransaction(() -> {
                        write.jdbc.update("insert into foundation_effect values (?,?)", UUID.randomUUID(), "aborted");
                        String writeXid = write.jdbc.queryForObject("select pg_current_xact_id()::text", String.class);
                        readRunner.runInTransaction(() -> {
                            assertNotEquals(writeXid, read.jdbc.queryForObject("select pg_current_xact_id()::text", String.class));
                            read.jdbc.update("insert into foundation_effect values (?,?)", UUID.randomUUID(), "independent");
                            return null;
                        });
                        throw new IllegalStateException("rollback WRITE only");
                    }));
                    assertEquals(0, write.observedEffects());
                    assertEquals(1, read.observedEffects(), "This deliberately demonstrates the absence of cross-realm atomicity");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class ExplicitRealms {
        @Bean TransactionRunner writeRunner(@Qualifier("writeManager") JpaTransactionManager manager) {
            return new SpringTransactionRunner(new TransactionTemplate(manager));
        }
        @Bean TransactionRunner readRunner(@Qualifier("readManager") JpaTransactionManager manager) {
            return new SpringTransactionRunner(new TransactionTemplate(manager));
        }
    }
}
