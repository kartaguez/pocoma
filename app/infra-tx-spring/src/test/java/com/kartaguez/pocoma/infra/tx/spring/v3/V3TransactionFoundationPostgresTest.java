package com.kartaguez.pocoma.infra.tx.spring.v3;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.awaitility.Awaitility;
import com.kartaguez.pocoma.infra.tx.spring.v3.fixture.FoundationEffect;

class V3TransactionFoundationPostgresTest extends PostgresFoundationTestSupport {
    private EntityManager entityManager() {
        return EntityManagerFactoryUtils.getTransactionalEntityManager(write.emf);
    }

    @Test
    void jpaAndJdbcSharePhysicalTransactionAndBecomeVisibleOnlyAfterCommit() {
        var afterCommit = new AtomicInteger();
        write.runner.runInTransaction(() -> {
            entityManager().persist(new FoundationEffect(UUID.randomUUID(), "JPA"));
            entityManager().flush();
            write.jdbc.update("insert into foundation_effect values (?,?)", UUID.randomUUID(), "JDBC");
            Object[] jpa = (Object[]) entityManager().createNativeQuery("select pg_backend_pid(), pg_current_xact_id()::text")
                    .getSingleResult();
            var jdbc = write.jdbc.queryForMap("select pg_backend_pid() pid, pg_current_xact_id()::text xid");
            assertEquals(((Number) jpa[0]).intValue(), ((Number) jdbc.get("pid")).intValue());
            assertEquals(jpa[1], jdbc.get("xid"));
            assertEquals(0, write.observedEffects());
            write.runner.runAfterCommit(() -> afterCommit.set(write.observedEffects()));
            assertEquals(0, afterCommit.get());
            return null;
        });
        assertEquals(2, write.observedEffects());
        assertEquals(2, afterCommit.get());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(TransactionSynchronizationManager.hasResource(write.dataSource));
    }

    @Test
    void rollbackInBothWriteOrdersLeavesNoJpaOrJdbcEffect() {
        for (boolean jpaFirst : new boolean[] { true, false }) {
            assertThrows(InjectedFailure.class, () -> write.runner.runInTransaction(() -> {
                Runnable jpa = () -> { entityManager().persist(new FoundationEffect(UUID.randomUUID(), "JPA")); entityManager().flush(); };
                Runnable jdbc = () -> write.jdbc.update("insert into foundation_effect values (?,?)", UUID.randomUUID(), "JDBC");
                if (jpaFirst) { jpa.run(); jdbc.run(); } else { jdbc.run(); jpa.run(); }
                assertEquals(0, write.observedEffects());
                throw new InjectedFailure();
            }));
            assertEquals(0, write.observedEffects());
        }
    }

    @Test
    void caughtRequiredParticipantFailureStillRollsBackTheOwner() {
        assertThrows(UnexpectedRollbackException.class, () -> write.runner.runInTransaction(() -> {
            write.jdbc.update("insert into foundation_effect values (?,?)", UUID.randomUUID(), "outer");
            try {
                write.runner.runInTransaction(() -> {
                    entityManager().persist(new FoundationEffect(UUID.randomUUID(), "inner"));
                    entityManager().flush();
                    throw new InjectedFailure();
                });
            } catch (InjectedFailure expected) {
                // Deliberately intercepted: REQUIRED participation must doom the physical transaction.
            }
            return null;
        }));
        assertEquals(0, write.observedEffects());
    }

    @Test
    void deferredConstraintFailureAtCommitRestoresAllEffects() {
        assertThrows(RuntimeException.class, () -> write.runner.runInTransaction(() -> {
            entityManager().persist(new FoundationEffect(UUID.randomUUID(), "JPA"));
            entityManager().flush();
            write.jdbc.update("insert into foundation_effect values (?,?)", UUID.randomUUID(), "JDBC");
            write.jdbc.update("insert into foundation_commit_guard values (?,?)", UUID.randomUUID(), UUID.randomUUID());
            assertEquals(1, write.jdbc.queryForObject("select count(*) from foundation_commit_guard", Integer.class));
            return null;
        }));
        assertEquals(0, write.observedEffects());
        assertEquals(0, write.jdbc.queryForObject("select count(*) from foundation_commit_guard", Integer.class));
    }

    @Test
    void observerFailureAfterCommitDoesNotMeanRollback() {
        assertThrows(InjectedFailure.class, () -> write.runner.runInTransaction(() -> {
            write.jdbc.update("insert into foundation_effect values (?,?)", UUID.randomUUID(), "committed");
            write.runner.runAfterCommit(() -> { throw new InjectedFailure(); });
            return null;
        }));
        assertEquals(1, write.observedEffects());
    }

    @Test
    void contendingConnectionsWaitForPhysicalRollbackBeforeCommitting() throws Exception {
        UUID id = UUID.randomUUID();
        write.jdbc.update("insert into foundation_effect values (?,?)", id, "initial");
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstPid = new AtomicInteger();
        var secondPid = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> first = pool.submit(() -> write.runner.runInTransaction(() -> {
                firstPid.set(write.jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                write.jdbc.update("update foundation_effect set payload='rolled-back' where id=?", id);
                held.countDown();
                await(release);
                throw new InjectedFailure();
            }));
            Future<?> second = null;
            try {
                assertTrue(held.await(10, TimeUnit.SECONDS));
                second = pool.submit(() -> write.runner.runInTransaction(() -> {
                    write.jdbc.execute("set local lock_timeout='10s'");
                    secondPid.set(write.jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    write.jdbc.update("update foundation_effect set payload='winner' where id=?", id);
                    return null;
                }));
                Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> secondPid.get() != 0
                        && Boolean.TRUE.equals(write.jdbc.queryForObject("select ? = any(pg_blocking_pids(?))",
                                Boolean.class, firstPid.get(), secondPid.get())));
                assertEquals("initial", write.jdbc.queryForObject("select payload from foundation_effect where id=?", String.class, id));
            } finally {
                release.countDown();
            }
            assertInstanceOf(InjectedFailure.class, assertThrows(ExecutionException.class,
                    () -> first.get(10, TimeUnit.SECONDS)).getCause());
            if (second != null) second.get(10, TimeUnit.SECONDS);
        }
        assertEquals("winner", write.jdbc.queryForObject("select payload from foundation_effect where id=?", String.class, id));
        assertEquals(1, write.observedEffects());
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }

    static final class InjectedFailure extends RuntimeException {}
}
