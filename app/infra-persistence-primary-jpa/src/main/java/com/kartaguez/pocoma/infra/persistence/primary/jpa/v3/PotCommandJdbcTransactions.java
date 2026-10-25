package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommandTransactions;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.jdbc.datasource.*;
import org.springframework.orm.jpa.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;

import javax.sql.DataSource;

/** Owner of the V3 Pot physical unit. No ambient transaction or nested independent effects. */
public final class PotCommandJdbcTransactions implements PotCommandTransactions {
    private record Key(DataSource source) {}

    private static final class Unit {
        final TransactionStatus status;
        final DataSource source;
        final Object connection;
        final EntityManagerFactory emf;
        final Object entityManager;
        boolean publicationClosed;

        Unit(TransactionStatus status, DataSource source, EntityManagerFactory emf) {
            this.status = status;
            this.source = source;
            this.emf = emf;
            connection = TransactionSynchronizationManager.getResource(source);
            entityManager = emf == null ? null : TransactionSynchronizationManager.getResource(emf);
        }

        void open() {
            if (publicationClosed
                    || status.isRollbackOnly()
                    || !TransactionSynchronizationManager.isActualTransactionActive()
                    || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    || !(connection instanceof ConnectionHolder)
                    || TransactionSynchronizationManager.getResource(source) != connection
                    || (emf != null
                            && TransactionSynchronizationManager.getResource(emf) != entityManager))
                throw new IllegalTransactionStateException(
                        "Pot physical unit changed, closed or poisoned");
        }
    }

    private final DataSource source;
    private final EntityManagerFactory emf;
    private final TransactionTemplate snapshot, write;

    public PotCommandJdbcTransactions(DataSource source, PlatformTransactionManager manager) {
        this.source = Objects.requireNonNull(source);
        if (manager instanceof JpaTransactionManager jpa
                && jpa.getDataSource() == source
                && !jpa.isNestedTransactionAllowed()) {
            emf = Objects.requireNonNull(jpa.getEntityManagerFactory());
        } else if (manager instanceof DataSourceTransactionManager jdbc
                && jdbc.getDataSource() == source
                && !jdbc.isNestedTransactionAllowed()) {
            emf = null;
        } else
            throw new IllegalArgumentException(
                    "Pot writers require one manager and the identical WRITE datasource; nesting"
                        + " forbidden");
        // A server-side COMMIT rejection must resolve by rollback, even with JDBC managers.
        // Transport failures remain UNKNOWN regardless of afterCompletion (see run).
        ((AbstractPlatformTransactionManager) manager).setRollbackOnCommitFailure(true);
        snapshot = new TransactionTemplate(manager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    void requireSource(DataSource expected) {
        if (source != expected)
            throw new IllegalArgumentException(
                    "Command store and transaction owner must share the WRITE datasource");
    }

    @Override
    public <T> T snapshot(Supplier<T> action) {
        return run(snapshot, action);
    }

    @Override
    public <T> T write(Supplier<T> action) {
        return run(write, action);
    }

    private <T> T run(TransactionTemplate template, Supplier<T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalTransactionStateException("Pot owner requires no ambient transaction");
        int[] completion = {TransactionSynchronization.STATUS_UNKNOWN};
        boolean[] committing = {false};
        try {
            T result =
                    template.execute(
                            status -> {
                                var unit = new Unit(status, source, emf);
                                TransactionSynchronizationManager.bindResource(
                                        new Key(source), unit);
                                TransactionSynchronizationManager.registerSynchronization(
                                        new TransactionSynchronization() {
                                            @Override
                                            public void afterCompletion(int outcome) {
                                                completion[0] = outcome;
                                                TransactionSynchronizationManager
                                                        .unbindResourceIfPossible(new Key(source));
                                            }
                                        });
                                try {
                                    var value = action.get();
                                    if (status.isRollbackOnly())
                                        throw new UnexpectedRollbackException(
                                                "Pot writer poisoned the physical unit");
                                    committing[0] = true;
                                    return value;
                                } catch (RuntimeException | Error failure) {
                                    status.setRollbackOnly();
                                    throw failure;
                                }
                            });
            if (completion[0] != TransactionSynchronization.STATUS_COMMITTED)
                throw new Failure(
                        Resolution.UNKNOWN, new IllegalStateException("Commit unconfirmed"));
            return result;
        } catch (Failure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // JDBC transport failure at COMMIT can follow successful server commit, regardless of
            // manager callbacks.
            boolean unknown = committing[0] && transportFailure(failure);
            throw new Failure(
                    !unknown && completion[0] == TransactionSynchronization.STATUS_ROLLED_BACK
                            ? Resolution.ROLLED_BACK
                            : Resolution.UNKNOWN,
                    failure);
        } finally {
            TransactionSynchronizationManager.unbindResourceIfPossible(new Key(source));
        }
    }

    static <T> T guarded(DataSource source, Supplier<T> action) {
        var unit = (Unit) TransactionSynchronizationManager.getResource(new Key(source));
        try {
            if (unit == null)
                throw new IllegalTransactionStateException(
                        "V3 Command writer requires its physical owner");
            unit.open();
            return action.get();
        } catch (RuntimeException | Error failure) {
            poisonUnits();
            poisonConnection(source);
            throw failure;
        }
    }

    /**
     * B primitives remain usable under their caller's transaction, but caught failures now poison
     * it.
     */
    static void revision(DataSource source, Runnable action) {
        var unit = (Unit) TransactionSynchronizationManager.getResource(new Key(source));
        try {
            if (unit != null) unit.open();
            else if (TransactionSynchronizationManager.getResourceMap().values().stream()
                    .anyMatch(Unit.class::isInstance))
                throw new IllegalTransactionStateException(
                        "Revision writer belongs to a different physical unit");
            action.run();
        } catch (RuntimeException | Error failure) {
            poisonUnits();
            poisonConnection(source);
            throw failure;
        }
    }

    private static void poisonUnits() {
        TransactionSynchronizationManager.getResourceMap().values().stream()
                .filter(Unit.class::isInstance)
                .map(Unit.class::cast)
                .forEach(unit -> unit.status.setRollbackOnly());
    }

    private static void poisonConnection(DataSource source) {
        if (TransactionSynchronizationManager.getResource(source)
                instanceof ConnectionHolder holder) holder.setRollbackOnly();
    }

    void flushBeforePublication() {
        if (emf != null) {
            var holder = (EntityManagerHolder) TransactionSynchronizationManager.getResource(emf);
            if (holder == null)
                throw new IllegalTransactionStateException("Missing WRITE EntityManager");
            holder.getEntityManager().flush();
        }
    }

    static void closePublication(DataSource source) {
        var unit = (Unit) TransactionSynchronizationManager.getResource(new Key(source));
        if (unit == null) throw new IllegalTransactionStateException("Missing Pot unit");
        unit.open();
        unit.publicationClosed = true;
    }

    private static boolean transportFailure(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var pending = new ArrayDeque<Throwable>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            var next = pending.remove();
            if (!seen.add(next)) continue;
            if (next instanceof SQLException sql) {
                var state = sql.getSQLState();
                if (state != null && (state.startsWith("08") || state.equals("40003"))) return true;
                if (sql.getNextException() != null) pending.add(sql.getNextException());
            }
            if (next.getCause() != null) pending.add(next.getCause());
        }
        return false;
    }
}
