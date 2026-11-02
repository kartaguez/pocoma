package com.kartaguez.pocoma.infra.tx.spring.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.infra.tx.spring.v3.fixture.FoundationEffect;
import com.kartaguez.pocoma.port.transaction.v3.*;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.orm.jpa.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * The same owner on WRITE JPA/JDBC and a distinct READ JDBC authority. No projections certified.
 */
class AttemptTransactionsPostgresTest extends PostgresFoundationTestSupport {
    SpringAttemptTransactions owner;

    EntityManager em() {
        return EntityManagerFactoryUtils.getTransactionalEntityManager(write.emf);
    }

    @BeforeEach
    void owner() {
        write.manager.setNestedTransactionAllowed(false);
        owner = new SpringAttemptTransactions(write.dataSource, write.manager, 20);
        for (var realm : List.of(write, read))
            realm.jdbc.execute("create table fixture_owner_slot(id int primary key)");
    }

    <T> T guarded(java.util.function.Supplier<T> action) {
        return SpringAttemptTransactions.guarded(write.dataSource, action);
    }

    void close() {
        owner.flushBeforePublication();
        SpringAttemptTransactions.closePublication(write.dataSource);
        owner.requirePublicationClosed();
    }

    @Test
    void jpaJdbcSamePidXidAndClosedContextCannotDirtyWriteAtCommit() throws Exception {
        UUID id = new UUID(0, 1);
        owner.write(
                () -> {
                    var entity = new FoundationEffect(id, "flushed");
                    guarded(
                            () -> {
                                em().persist(entity);
                                return null;
                            });
                    var jdbc =
                            write.jdbc.queryForMap(
                                    "select pg_backend_pid() pid,pg_current_xact_id()::text xid");
                    var jpa =
                            (Object[])
                                    em().createNativeQuery(
                                                    "select"
                                                        + " pg_backend_pid(),pg_current_xact_id()::text")
                                            .getSingleResult();
                    assertEquals(
                            ((Number) jdbc.get("pid")).intValue(), ((Number) jpa[0]).intValue());
                    assertEquals(jdbc.get("xid"), jpa[1]);
                    close();
                    assertFalse(em().contains(entity));
                    entity.setPayload("late dirty reference");
                    TransactionSynchronizationManager.registerSynchronization(
                            new TransactionSynchronization() {
                                public void beforeCommit(boolean readOnly) {
                                    entity.setPayload("late callback dirty reference");
                                }
                            });
                    return null;
                });
        assertEquals(
                "flushed",
                write.jdbc.queryForObject(
                        "select payload from foundation_effect where id=?", String.class, id));
    }

    @Test
    void guardedLateCallbackRefusedBeforeSqlAndRollback() {
        for (boolean intercept : List.of(false, true)) {
            assertThrows(
                    AttemptTransactions.Failure.class,
                    () ->
                            owner.write(
                                    () -> {
                                        guarded(
                                                () -> {
                                                    em().persist(
                                                                    new FoundationEffect(
                                                                            new UUID(0, 1),
                                                                            "prefix"));
                                                    return null;
                                                });
                                        close();
                                        TransactionSynchronizationManager.registerSynchronization(
                                                new TransactionSynchronization() {
                                                    public void beforeCommit(boolean readOnly) {
                                                        try {
                                                            guarded(
                                                                    () -> {
                                                                        write.jdbc.update(
                                                                                "insert into"
                                                                                    + " foundation_effect"
                                                                                    + " values"
                                                                                    + " (?,?)",
                                                                                new UUID(0, 2),
                                                                                "late");
                                                                        return null;
                                                                    });
                                                        } catch (RuntimeException failure) {
                                                            if (!intercept) throw failure;
                                                        }
                                                    }
                                                });
                                        return null;
                                    }));
            assertEquals(0, write.observedEffects(), "caught=" + intercept);
        }
    }

    @Test
    void jdbcCaughtLateCallbackCannotCommitPrefix() {
        var manager = new DataSourceTransactionManager(read.dataSource);
        manager.setNestedTransactionAllowed(false);
        var readOwner = new SpringAttemptTransactions(read.dataSource, manager, 20);
        for (boolean completionPhase : List.of(false, true)) {
            var failure =
                    assertThrows(
                            AttemptTransactions.Failure.class,
                            () ->
                                    readOwner.write(
                                            () -> {
                                                SpringAttemptTransactions.guarded(
                                                        read.dataSource,
                                                        () -> {
                                                            read.jdbc.update(
                                                                    "insert into foundation_effect"
                                                                            + " values (?,?)",
                                                                    new UUID(0, 19),
                                                                    "late guard prefix");
                                                            return null;
                                                        });
                                                SpringAttemptTransactions.closePublication(
                                                        read.dataSource);
                                                TransactionSynchronizationManager
                                                        .registerSynchronization(
                                                                new TransactionSynchronization() {
                                                                    void late() {
                                                                        try {
                                                                            SpringAttemptTransactions
                                                                                    .guarded(
                                                                                            read.dataSource,
                                                                                            () ->
                                                                                                    null);
                                                                        } catch (
                                                                                RuntimeException
                                                                                        intercepted) {
                                                                            /* Must poison even when caught. */
                                                                        }
                                                                    }

                                                                    public void beforeCommit(
                                                                            boolean readOnly) {
                                                                        if (!completionPhase)
                                                                            late();
                                                                    }

                                                                    public void beforeCompletion() {
                                                                        if (completionPhase) late();
                                                                    }
                                                                });
                                                return null;
                                            }));
            assertEquals(AttemptTransactions.Resolution.UNKNOWN, failure.resolution());
            assertEquals(0, read.observedEffects());
        }
    }

    @Test
    void caughtApplicationJdbcJpaFailuresPoisonBothWriteOrders() {
        for (String kind : List.of("application", "jdbc", "jpa"))
            for (boolean jpaFirst : List.of(false, true)) {
                var failure =
                        assertThrows(
                                AttemptTransactions.Failure.class,
                                () ->
                                        owner.write(
                                                () -> {
                                                    if (jpaFirst)
                                                        guarded(
                                                                () -> {
                                                                    em().persist(
                                                                                    new FoundationEffect(
                                                                                            new UUID(
                                                                                                    0,
                                                                                                    1),
                                                                                            "JPA"));
                                                                    em().flush();
                                                                    return null;
                                                                });
                                                    else
                                                        guarded(
                                                                () -> {
                                                                    write.jdbc.update(
                                                                            "insert into"
                                                                                + " foundation_effect"
                                                                                + " values (?,?)",
                                                                            new UUID(0, 2),
                                                                            "JDBC");
                                                                    return null;
                                                                });
                                                    try {
                                                        guarded(
                                                                () -> {
                                                                    switch (kind) {
                                                                        case "application" ->
                                                                                throw new IllegalArgumentException(
                                                                                        "caught"
                                                                                            + " validation");
                                                                        case "jdbc" ->
                                                                                write.jdbc.execute(
                                                                                        "select"
                                                                                            + " 1/0");
                                                                        case "jpa" -> {
                                                                            em().persist(
                                                                                            new FoundationEffect(
                                                                                                    new UUID(
                                                                                                            0,
                                                                                                            3),
                                                                                                    null));
                                                                            em().flush();
                                                                        }
                                                                    }
                                                                    return null;
                                                                });
                                                    } catch (RuntimeException caught) {
                                                    }
                                                    return null;
                                                }));
                assertEquals(
                        AttemptTransactions.Resolution.ROLLED_BACK,
                        failure.resolution(),
                        kind + " " + jpaFirst);
                if (kind.equals("jdbc")) {
                    Throwable original = failure.getCause();
                    while (original.getCause() != null) original = original.getCause();
                    assertInstanceOf(SQLException.class, original);
                    assertEquals("22012", ((SQLException) original).getSQLState());
                }
                assertEquals(0, write.observedEffects());
            }
    }

    @Test
    void autonomousRevisionCaughtFailurePoisonsJpaAndJdbcCallers() {
        var jdbc = new DataSourceTransactionManager(write.dataSource);
        for (PlatformTransactionManager manager : List.of(write.manager, jdbc))
            for (boolean jpaPrefix :
                    manager == write.manager ? List.of(false, true) : List.of(false)) {
                assertThrows(
                        UnexpectedRollbackException.class,
                        () ->
                                new TransactionTemplate(manager)
                                        .execute(
                                                status -> {
                                                    if (jpaPrefix) {
                                                        em().persist(
                                                                        new FoundationEffect(
                                                                                new UUID(0, 17),
                                                                                "autonomous JPA"));
                                                        em().flush();
                                                    } else
                                                        write.jdbc.update(
                                                                "insert into foundation_effect"
                                                                    + " values (?,?)",
                                                                new UUID(0, 17),
                                                                "autonomous JDBC");
                                                    try {
                                                        SpringAttemptTransactions.revision(
                                                                write.dataSource,
                                                                () -> {
                                                                    throw new IllegalArgumentException(
                                                                            "caught autonomous B"
                                                                                + " validation");
                                                                });
                                                    } catch (IllegalArgumentException intercepted) {
                                                        assertEquals(
                                                                "caught autonomous B validation",
                                                                intercepted.getMessage());
                                                    }
                                                    return null;
                                                }));
                assertEquals(0, write.observedEffects());
            }
    }

    @Test
    void requiresNewJpaAndJdbcCannotCommitEffectOrDelete() {
        write.jdbc.update("insert into fixture_owner_slot values (1)");
        for (boolean jpa : List.of(false, true))
            for (boolean delete : List.of(false, true)) {
                PlatformTransactionManager manager;
                if (jpa) manager = write.manager;
                else {
                    var jdbc = new DataSourceTransactionManager(write.dataSource);
                    jdbc.setNestedTransactionAllowed(false);
                    manager = jdbc;
                }
                var inner = new TransactionTemplate(manager);
                inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                var reachedSql = new AtomicBoolean();
                AttemptTransactions.Failure observed = null;
                try {
                    owner.write(
                            () -> {
                                try {
                                    guarded(
                                            () ->
                                                    inner.execute(
                                                            s ->
                                                                    guarded(
                                                                            () -> {
                                                                                reachedSql.set(
                                                                                        true);
                                                                                if (delete)
                                                                                    write.jdbc
                                                                                            .update(
                                                                                                    "delete"
                                                                                                        + " from"
                                                                                                        + " fixture_owner_slot"
                                                                                                        + " where"
                                                                                                        + " id=1");
                                                                                else
                                                                                    write.jdbc
                                                                                            .update(
                                                                                                    "insert"
                                                                                                        + " into"
                                                                                                        + " foundation_effect"
                                                                                                        + " values"
                                                                                                        + " (?,?)",
                                                                                                    new UUID(
                                                                                                            0,
                                                                                                            5),
                                                                                                    "independent");
                                                                                return null;
                                                                            })));
                                } catch (RuntimeException caught) {
                                    System.err.println("INNER REFUSED: " + caught);
                                }
                                return null;
                            });
                } catch (AttemptTransactions.Failure failure) {
                    observed = failure;
                }
                System.err.println(
                        "PROBE "
                                + manager.getClass().getSimpleName()
                                + " delete="
                                + delete
                                + " reached="
                                + reachedSql.get()
                                + " effects="
                                + write.observedEffects()
                                + " failure="
                                + observed);
                assertFalse(reachedSql.get());
                assertEquals(0, write.observedEffects());
                assertNotNull(observed);
                assertEquals(
                        1,
                        write.jdbc.queryForObject(
                                "select count(*) from fixture_owner_slot", Integer.class));
            }
    }

    @Test
    void nestedAmbientWrongSourceAndWrongEmfRefused() {
        var nested = new TransactionTemplate(write.manager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        assertThrows(
                AttemptTransactions.Failure.class,
                () ->
                        owner.write(
                                () -> {
                                    try {
                                        guarded(() -> nested.execute(s -> 1));
                                    } catch (RuntimeException caught) {
                                    }
                                    return null;
                                }));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SpringAttemptTransactions(read.dataSource, write.manager));
        var wrongEmf = new JpaTransactionManager(read.emf);
        wrongEmf.setDataSource(write.dataSource);
        wrongEmf.setNestedTransactionAllowed(false);
        assertThrows(
                IllegalArgumentException.class,
                () -> new SpringAttemptTransactions(write.dataSource, wrongEmf));
        new TransactionTemplate(write.manager)
                .execute(
                        s -> {
                            assertThrows(
                                    IllegalTransactionStateException.class,
                                    () -> owner.write(() -> null));
                            return null;
                        });
        assertThrows(
                AttemptTransactions.Failure.class,
                () ->
                        owner.write(
                                () -> {
                                    try {
                                        SpringAttemptTransactions.revision(
                                                read.dataSource,
                                                () ->
                                                        read.jdbc.update(
                                                                "insert into foundation_effect"
                                                                        + " values (?,?)",
                                                                new UUID(0, 1),
                                                                "wrong"));
                                    } catch (RuntimeException caught) {
                                    }
                                    return null;
                                }));
        assertEquals(0, read.observedEffects());
    }

    @Test
    void sameOwnerReadAuthorityAtomicAndContextCannotCrossAuthorityOrLifetime() {
        var manager = new DataSourceTransactionManager(read.dataSource);
        manager.setNestedTransactionAllowed(false);
        var readOwner = new SpringAttemptTransactions(read.dataSource, manager, 20);
        read.jdbc.update("insert into fixture_owner_slot values(1)");
        for (boolean fail : List.of(true, false)) {
            java.util.function.Supplier<Void> action =
                    () -> {
                        SpringAttemptTransactions.guarded(
                                read.dataSource,
                                () -> {
                                    read.jdbc.update(
                                            "insert into foundation_effect values (?,?)",
                                            new UUID(0, 1),
                                            "READ technical effect");
                                    assertEquals(
                                            1,
                                            read.jdbc.update(
                                                    "delete from fixture_owner_slot where id=1"));
                                    return null;
                                });
                        SpringAttemptTransactions.closePublication(read.dataSource);
                        readOwner.requirePublicationClosed();
                        if (fail) throw new IllegalStateException("after READ delete");
                        return null;
                    };
            if (fail) {
                assertThrows(AttemptTransactions.Failure.class, () -> readOwner.write(action));
                assertEquals(0, read.observedEffects());
            } else readOwner.write(action);
        }
        assertEquals(1, read.observedEffects());
        assertEquals(0, write.observedEffects());
        var saved = new AtomicReference<RecoveryReadContext>();
        var refused =
                assertThrows(
                        AttemptTransactions.Failure.class,
                        () ->
                                readOwner.read(
                                        context -> {
                                            saved.set(context);
                                            SpringAttemptTransactions.requireRead(
                                                    read.dataSource, context);
                                            assertEquals(
                                                    "on",
                                                    read.jdbc.queryForObject(
                                                            "show transaction_read_only",
                                                            String.class));
                                            assertEquals(
                                                    "repeatable read",
                                                    read.jdbc.queryForObject(
                                                            "show transaction_isolation",
                                                            String.class));
                                            assertThrows(
                                                    RuntimeException.class,
                                                    () ->
                                                            SpringAttemptTransactions.requireRead(
                                                                    write.dataSource, context));
                                            return null;
                                        }));
        assertEquals(AttemptTransactions.Resolution.ROLLED_BACK, refused.resolution());
        assertEquals(1, read.observedEffects());
        assertThrows(RuntimeException.class, saved.get()::requireActiveRead);
        assertThrows(
                AttemptTransactions.Failure.class,
                () ->
                        readOwner.read(
                                context -> {
                                    read.jdbc.execute("delete from foundation_effect");
                                    return null;
                                }));
        assertEquals(1, read.observedEffects());
    }

    @Test
    void commitServerRejectionRollbackAndMaskedCommitRemainDistinct() {
        var rejected =
                assertThrows(
                        AttemptTransactions.Failure.class,
                        () ->
                                owner.write(
                                        () -> {
                                            guarded(
                                                    () -> {
                                                        write.jdbc.update(
                                                                "insert into foundation_effect"
                                                                        + " values (?,?)",
                                                                new UUID(0, 1),
                                                                "rollback");
                                                        write.jdbc.update(
                                                                "insert into"
                                                                        + " foundation_commit_guard"
                                                                        + " values (?,?)",
                                                                new UUID(0, 2),
                                                                new UUID(0, 3));
                                                        return null;
                                                    });
                                            return null;
                                        }));
        assertEquals(AttemptTransactions.Resolution.ROLLED_BACK, rejected.resolution());
        assertEquals(0, write.observedEffects());
        var masked =
                new DataSourceTransactionManager(write.dataSource) {
                    @Override
                    protected void doCommit(DefaultTransactionStatus status) {
                        super.doCommit(status);
                        throw new IllegalStateException("commit cause lost by manager");
                    }
                };
        masked.setNestedTransactionAllowed(false);
        var maskingOwner = new SpringAttemptTransactions(write.dataSource, masked, 20);
        var uncertain =
                assertThrows(
                        AttemptTransactions.Failure.class,
                        () ->
                                maskingOwner.write(
                                        () -> {
                                            write.jdbc.update(
                                                    "insert into foundation_effect values (?,?)",
                                                    new UUID(0, 4),
                                                    "server committed");
                                            return null;
                                        }));
        assertEquals(AttemptTransactions.Resolution.UNKNOWN, uncertain.resolution());
        assertEquals(1, write.observedEffects());
    }

    @Test
    void originalCallbackCauseSurvivesRollbackFailureAndConfirmedCommitWinsCleanup() {
        var broken =
                new DataSourceTransactionManager(write.dataSource) {
                    @Override
                    protected void doRollback(DefaultTransactionStatus status) {
                        super.doRollback(status);
                        throw new TransactionSystemException(
                                "rollback ack missing", new SQLException("transport", "08006"));
                    }
                };
        broken.setNestedTransactionAllowed(false);
        var brokenOwner = new SpringAttemptTransactions(write.dataSource, broken, 20);
        var original = new IllegalArgumentException("original callback cause");
        var failure =
                assertThrows(
                        AttemptTransactions.Failure.class,
                        () ->
                                brokenOwner.write(
                                        () -> {
                                            throw original;
                                        }));
        assertEquals(AttemptTransactions.Resolution.UNKNOWN, failure.resolution());
        assertSame(original, failure.getCause());
        assertEquals(1, original.getSuppressed().length);
        var committed =
                assertThrows(
                        AttemptTransactions.Failure.class,
                        () ->
                                owner.write(
                                        () -> {
                                            guarded(
                                                    () -> {
                                                        write.jdbc.update(
                                                                "insert into foundation_effect"
                                                                        + " values (?,?)",
                                                                new UUID(0, 1),
                                                                "committed");
                                                        return null;
                                                    });
                                            close();
                                            TransactionSynchronizationManager
                                                    .registerSynchronization(
                                                            new TransactionSynchronization() {
                                                                public void afterCommit() {
                                                                    throw new IllegalStateException(
                                                                            "cleanup failure after"
                                                                                    + " confirmed"
                                                                                    + " commit");
                                                                }
                                                            });
                                            return null;
                                        }));
        assertEquals(AttemptTransactions.Resolution.COMMITTED, committed.resolution());
        assertEquals(1, write.observedEffects());
    }

    private static final class SharedGuard implements AttemptTransactions.Guard {
        final AtomicReference<Throwable> cause = new AtomicReference<>();

        public void invalidate(Throwable fault) {
            cause.compareAndSet(null, fault);
        }

        public void requireValid() {
            if (cause.get() instanceof RuntimeException runtime) throw runtime;
            if (cause.get() instanceof Error error) throw error;
        }
    }

    @Test
    void i2ExpiredSourcePoisonsOtherAuthorityEvenWhenCaught() {
        var guard = new SharedGuard();
        var saved = new AtomicReference<RecoveryReadContext>();
        owner.read(
                context -> {
                    saved.set(context);
                    return null;
                },
                guard);
        var manager = new DataSourceTransactionManager(read.dataSource);
        manager.setNestedTransactionAllowed(false);
        var effects = new SpringAttemptTransactions(read.dataSource, manager, 20);
        var failure =
                assertThrows(
                        AttemptTransactions.Failure.class,
                        () ->
                                effects.read(
                                        context -> {
                                            SpringAttemptTransactions.requireRead(
                                                    read.dataSource, context);
                                            assertThrows(
                                                    IllegalTransactionStateException.class,
                                                    saved.get()::requireActiveRead);
                                            return null;
                                        },
                                        guard));
        assertEquals(AttemptTransactions.Resolution.ROLLED_BACK, failure.resolution());
        assertSame(guard.cause.get(), failure.getCause());
        assertThrows(
                IllegalTransactionStateException.class,
                () -> effects.write(() -> fail("no next begin"), guard));
        assertEquals(0, read.observedEffects());
        assertEquals(0, write.observedEffects());
    }

    @Test
    void i2SharedGuardLateCallbacksPreventJdbcAndJpaPhysicalCommit() {
        for (boolean jpa : List.of(false, true))
            for (boolean beforeCompletion : List.of(false, true)) {
                var guard = new SharedGuard();
                var saved = new AtomicReference<RecoveryReadContext>();
                owner.read(
                        context -> {
                            saved.set(context);
                            return null;
                        },
                        guard);
                var ds = jpa ? write.dataSource : read.dataSource;
                var jdbc = jpa ? write.jdbc : read.jdbc;
                var manager = new DataSourceTransactionManager(ds);
                manager.setNestedTransactionAllowed(false);
                var effects = jpa ? owner : new SpringAttemptTransactions(ds, manager, 20);
                jdbc.update("insert into fixture_owner_slot values(17)");
                var failure =
                        assertThrows(
                                AttemptTransactions.Failure.class,
                                () ->
                                        effects.write(
                                                () -> {
                                                    SpringAttemptTransactions.guarded(
                                                            ds,
                                                            () -> {
                                                                jdbc.update(
                                                                        "insert into"
                                                                            + " foundation_effect"
                                                                            + " values (?,?)",
                                                                        new UUID(0, 17),
                                                                        "prefix");
                                                                jdbc.update(
                                                                        "delete from"
                                                                            + " fixture_owner_slot"
                                                                            + " where id=17");
                                                                return null;
                                                            });
                                                    if (jpa) effects.flushBeforePublication();
                                                    SpringAttemptTransactions.closePublication(ds);
                                                    TransactionSynchronizationManager
                                                            .registerSynchronization(
                                                                    new TransactionSynchronization() {
                                                                        void fault() {
                                                                            assertThrows(
                                                                                    IllegalTransactionStateException
                                                                                            .class,
                                                                                    saved.get()
                                                                                            ::requireActiveRead);
                                                                        }

                                                                        public void beforeCommit(
                                                                                boolean readOnly) {
                                                                            if (!beforeCompletion)
                                                                                fault();
                                                                        }

                                                                        public void
                                                                                beforeCompletion() {
                                                                            if (beforeCompletion)
                                                                                fault();
                                                                        }
                                                                    });
                                                    return null;
                                                },
                                                guard));
                // L2 remains conservative once handed to Spring's commit path.
                assertEquals(AttemptTransactions.Resolution.UNKNOWN, failure.resolution());
                assertSame(guard.cause.get(), failure.getCause());
                assertEquals(
                        0,
                        jdbc.queryForObject(
                                "select count(*) from foundation_effect where id=?",
                                Integer.class,
                                new UUID(0, 17)));
                assertEquals(
                        1,
                        jdbc.queryForObject(
                                "select count(*) from fixture_owner_slot where id=17",
                                Integer.class));
                jdbc.update("delete from fixture_owner_slot where id=17");
            }
    }

    @Test
    void i2PoisonAfterCompletionCannotRewriteConfirmedPhysicalCommit() {
        for (boolean jpa : List.of(false, true)) {
            var guard = new SharedGuard();
            var saved = new AtomicReference<RecoveryReadContext>();
            owner.read(
                    context -> {
                        saved.set(context);
                        return null;
                    },
                    guard);
            var ds = jpa ? write.dataSource : read.dataSource;
            var jdbc = jpa ? write.jdbc : read.jdbc;
            var manager = new DataSourceTransactionManager(ds);
            manager.setNestedTransactionAllowed(false);
            var effects = jpa ? owner : new SpringAttemptTransactions(ds, manager, 20);
            jdbc.update("insert into fixture_owner_slot values(19)");
            effects.write(
                    () -> {
                        SpringAttemptTransactions.guarded(
                                ds,
                                () -> {
                                    jdbc.update(
                                            "insert into foundation_effect values (?,?)",
                                            new UUID(0, 19),
                                            "committed");
                                    jdbc.update("delete from fixture_owner_slot where id=19");
                                    return null;
                                });
                        if (jpa) effects.flushBeforePublication();
                        SpringAttemptTransactions.closePublication(ds);
                        TransactionSynchronizationManager.registerSynchronization(
                                new TransactionSynchronization() {
                                    public void afterCompletion(int status) {
                                        assertEquals(STATUS_COMMITTED, status);
                                        assertThrows(
                                                IllegalTransactionStateException.class,
                                                saved.get()::requireActiveRead);
                                    }
                                });
                        return null;
                    },
                    guard);
            assertNotNull(guard.cause.get());
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "select count(*) from foundation_effect where id=?",
                            Integer.class,
                            new UUID(0, 19)));
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "select count(*) from fixture_owner_slot where id=19", Integer.class));
            assertThrows(
                    IllegalTransactionStateException.class,
                    () -> effects.read(c -> fail("late SQL"), guard));
        }
    }
}
