package com.kartaguez.pocoma.infra.tx.spring.v3;

import com.kartaguez.pocoma.port.transaction.v3.*;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.orm.jpa.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

import java.sql.SQLException;
import java.util.*;
import java.util.function.*;

import javax.sql.DataSource;

/** Spring owns begin/commit/rollback. This adapter retains the minimal L1 participation guards. */
public final class SpringAttemptTransactions implements AttemptTransactions {
    private record Key(DataSource source) {}

    private static final class Unit implements RecoveryReadContext {
        final TransactionStatus status;
        final DataSource source;
        final Object connection;
        final EntityManagerFactory emf;
        final Object entityManager;
        final Thread thread = Thread.currentThread();
        final boolean read;
        final TransactionSynchronization synchronization;
        boolean active = true;
        boolean publicationClosed;

        Unit(
                TransactionStatus status,
                DataSource source,
                EntityManagerFactory emf,
                boolean read,
                TransactionSynchronization synchronization) {
            this.status = status;
            this.source = source;
            this.emf = emf;
            this.read = read;
            this.synchronization = synchronization;
            connection = TransactionSynchronizationManager.getResource(source);
            entityManager = emf == null ? null : TransactionSynchronizationManager.getResource(emf);
        }

        void requireCurrent() {
            if (!active
                    || Thread.currentThread() != thread
                    || status.isRollbackOnly()
                    || !TransactionSynchronizationManager.isActualTransactionActive()
                    // A second manager can reuse the JPA JDBC holder yet start/commit its own
                    // transaction. Spring suspends the owner's synchronizations even in that case.
                    || !TransactionSynchronizationManager.isSynchronizationActive()
                    || !TransactionSynchronizationManager.getSynchronizations()
                            .contains(synchronization)
                    || !(connection instanceof ConnectionHolder)
                    || TransactionSynchronizationManager.getResource(new Key(source)) != this
                    || TransactionSynchronizationManager.getResource(source) != connection
                    || (emf != null
                            && (!(entityManager instanceof EntityManagerHolder)
                                    || TransactionSynchronizationManager.getResource(emf)
                                            != entityManager)))
                throw new IllegalTransactionStateException(
                        "Attempt unit changed, expired or poisoned");
        }

        void open() {
            requireCurrent();
            if (read
                    || publicationClosed
                    || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                throw new IllegalTransactionStateException(
                        "Attempt write publication closed or read-only");
        }

        @Override
        public void requireActiveRead() {
            requireCurrent();
            if (!read
                    || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    || !Objects.equals(
                            TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                            TransactionDefinition.ISOLATION_REPEATABLE_READ))
                throw new IllegalTransactionStateException(
                        "Recovery requires its repeatable read snapshot");
        }
    }

    private final DataSource source;
    private final EntityManagerFactory emf;
    private final TransactionTemplate read, write;

    public SpringAttemptTransactions(DataSource source, PlatformTransactionManager manager) {
        this(source, manager, 60);
    }

    public SpringAttemptTransactions(
            DataSource source, PlatformTransactionManager manager, int timeoutSeconds) {
        this.source = Objects.requireNonNull(source);
        if (timeoutSeconds < 1)
            throw new IllegalArgumentException("Positive attempt timeout required");
        if (manager instanceof JpaTransactionManager jpa
                && jpa.getDataSource() == source
                && !jpa.isNestedTransactionAllowed()) {
            emf = Objects.requireNonNull(jpa.getEntityManagerFactory());
            if (!(emf instanceof EntityManagerFactoryInfo info) || info.getDataSource() != source)
                throw new IllegalArgumentException(
                        "JPA factory must use the identical authority datasource");
        } else if (manager instanceof DataSourceTransactionManager jdbc
                && jdbc.getDataSource() == source
                && !jdbc.isNestedTransactionAllowed()) {
            emf = null;
        } else
            throw new IllegalArgumentException(
                    "One authority manager/source required; nesting forbidden");
        var spring = (AbstractPlatformTransactionManager) manager;
        spring.setRollbackOnCommitFailure(true);
        spring.setGlobalRollbackOnParticipationFailure(true);
        read =
                template(
                        manager,
                        TransactionDefinition.ISOLATION_REPEATABLE_READ,
                        true,
                        timeoutSeconds);
        write =
                template(
                        manager,
                        TransactionDefinition.ISOLATION_READ_COMMITTED,
                        false,
                        timeoutSeconds);
    }

    private static TransactionTemplate template(
            PlatformTransactionManager manager, int isolation, boolean readOnly, int timeout) {
        var template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        template.setIsolationLevel(isolation);
        template.setReadOnly(readOnly);
        template.setTimeout(timeout);
        return template;
    }

    public void requireSource(DataSource expected) {
        if (source != expected)
            throw new IllegalArgumentException("Store and owner must share datasource");
    }

    @Override
    public <T> T read(Function<RecoveryReadContext, T> action) {
        return run(
                read,
                true,
                unit -> {
                    new JdbcTemplate(source).execute("set transaction read only");
                    unit.requireActiveRead();
                    return action.apply(unit);
                });
    }

    @Override
    public <T> T write(Supplier<T> action) {
        return run(write, false, unit -> action.get());
    }

    private <T> T run(TransactionTemplate template, boolean reading, Function<Unit, T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            var failure =
                    new IllegalTransactionStateException(
                            "Attempt owner requires no ambient transaction");
            poisonUnits(failure);
            throw failure;
        }
        int[] completion = {TransactionSynchronization.STATUS_UNKNOWN};
        boolean[] handedToCommit = {false};
        Throwable[] callbackFailure = {null};
        try {
            T result =
                    template.execute(
                            status -> {
                                var synchronization =
                                        new TransactionSynchronization() {
                                            @Override
                                            public void afterCompletion(int outcome) {
                                                completion[0] = outcome;
                                            }
                                        };
                                var unit = new Unit(status, source, emf, reading, synchronization);
                                TransactionSynchronizationManager.bindResource(
                                        new Key(source), unit);
                                TransactionSynchronizationManager.registerSynchronization(
                                        synchronization);
                                try {
                                    T value = action.apply(unit);
                                    unit.requireCurrent();
                                    handedToCommit[0] = true;
                                    return value;
                                } catch (RuntimeException | Error failure) {
                                    callbackFailure[0] = failure;
                                    status.setRollbackOnly();
                                    throw failure;
                                } finally {
                                    // Keep the guard bound through Spring callbacks and physical
                                    // termination.
                                    // No writer or recovery context may re-enter after this
                                    // callback returns.
                                    unit.active = false;
                                }
                            });
            if (completion[0] != TransactionSynchronization.STATUS_COMMITTED)
                throw new Failure(
                        Resolution.UNKNOWN, new IllegalStateException("Commit unconfirmed"));
            return result;
        } catch (RuntimeException failure) {
            Throwable original = callbackFailure[0] != null ? callbackFailure[0] : failure;
            if (original != failure) original.addSuppressed(failure);
            var resolution =
                    completion[0] == TransactionSynchronization.STATUS_COMMITTED
                            ? Resolution.COMMITTED
                            : completion[0] == TransactionSynchronization.STATUS_ROLLED_BACK
                                            && !transportFailure(failure)
                                            && !transportFailure(original)
                                            && (!handedToCommit[0]
                                                    || explicitServerRejection(failure))
                                    ? Resolution.ROLLED_BACK
                                    : Resolution.UNKNOWN;
            throw new Failure(resolution, original);
        } finally {
            TransactionSynchronizationManager.unbindResourceIfPossible(new Key(source));
        }
    }

    @Override
    public void poison(Throwable cause) {
        Objects.requireNonNull(cause);
        poisonUnits(cause);
        poisonConnection(source);
    }

    @Override
    public void requirePublicationClosed() {
        var unit = current(source);
        try {
            if (unit == null) throw new IllegalTransactionStateException("Missing attempt unit");
            unit.requireCurrent();
            if (unit.read || !unit.publicationClosed)
                throw new IllegalTransactionStateException("Publication was not closed");
        } catch (RuntimeException | Error failure) {
            poison(failure);
            throw failure;
        }
    }

    public static <T> T guarded(DataSource source, Supplier<T> action) {
        try {
            var unit = current(source);
            if (unit == null)
                throw new IllegalTransactionStateException("Writer requires its attempt owner");
            unit.open();
            return action.get();
        } catch (RuntimeException | Error failure) {
            poisonUnits(failure);
            poisonConnection(source);
            throw failure;
        }
    }

    /** Autonomous L1-B callers are retained only when no Engine authority is active. */
    public static void revision(DataSource source, Runnable action) {
        try {
            var unit = current(source);
            if (unit != null) unit.open();
            else if (TransactionSynchronizationManager.getResourceMap().values().stream()
                    .anyMatch(Unit.class::isInstance))
                throw new IllegalTransactionStateException("Revision belongs to another authority");
            action.run();
        } catch (RuntimeException | Error failure) {
            poisonUnits(failure);
            poisonConnection(source);
            throw failure;
        }
    }

    public static void requireRead(DataSource source, RecoveryReadContext context) {
        context.requireActiveRead();
        var unit = current(source);
        if (unit == null || unit != context)
            throw new IllegalTransactionStateException("Observation belongs to another authority");
    }

    private static Unit current(DataSource source) {
        return (Unit) TransactionSynchronizationManager.getResource(new Key(source));
    }

    private static void poisonUnits(Throwable cause) {
        TransactionSynchronizationManager.getResourceMap().values().stream()
                .filter(Unit.class::isInstance)
                .map(Unit.class::cast)
                .forEach(
                        unit -> {
                            unit.status.setRollbackOnly();
                            // Like JpaTransactionManager.doSetRollbackOnly: after the owner
                            // callback,
                            // a beforeCommit listener can catch a guard failure. Spring's local
                            // flag
                            // alone is no longer checked there; JPA must also be globally
                            // rollback-only.
                            try {
                                if (unit.entityManager instanceof EntityManagerHolder holder) {
                                    var transaction = holder.getEntityManager().getTransaction();
                                    if (transaction.isActive()) transaction.setRollbackOnly();
                                }
                            } catch (RuntimeException markingFailure) {
                                if (markingFailure != cause) cause.addSuppressed(markingFailure);
                            }
                        });
    }

    private static void poisonConnection(DataSource source) {
        if (TransactionSynchronizationManager.getResource(source)
                instanceof ConnectionHolder holder) holder.setRollbackOnly();
    }

    public void flushBeforePublication() {
        guarded(
                source,
                () -> {
                    if (emf != null)
                        ((EntityManagerHolder) TransactionSynchronizationManager.getResource(emf))
                                .getEntityManager()
                                .flush();
                    return null;
                });
    }

    public static void closePublication(DataSource source) {
        guarded(
                source,
                () -> {
                    var unit = current(source);
                    if (unit.emf != null) {
                        var entityManager =
                                ((EntityManagerHolder) unit.entityManager).getEntityManager();
                        // Flush alone leaves managed references dirty-checkable at commit. Detach
                        // the
                        // flushed context so a retained entity/callback cannot enqueue late dirty
                        // writes.
                        entityManager.clear();
                    }
                    unit.publicationClosed = true;
                    return null;
                });
    }

    private static List<Throwable> causes(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var pending = new ArrayDeque<Throwable>();
        var result = new ArrayList<Throwable>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            var next = pending.remove();
            if (!seen.add(next)) continue;
            result.add(next);
            if (next.getCause() != null) pending.add(next.getCause());
            Collections.addAll(pending, next.getSuppressed());
            if (next instanceof SQLException sql && sql.getNextException() != null)
                pending.add(sql.getNextException());
            if (next instanceof TransactionSystemException system
                    && system.getOriginalException() != null)
                pending.add(system.getOriginalException());
        }
        return result;
    }

    private static boolean transportFailure(Throwable failure) {
        return causes(failure).stream()
                .filter(SQLException.class::isInstance)
                .map(SQLException.class::cast)
                .map(SQLException::getSQLState)
                .filter(Objects::nonNull)
                .anyMatch(state -> state.startsWith("08") || state.equals("40003"));
    }

    private static boolean explicitServerRejection(Throwable failure) {
        return causes(failure).stream()
                .filter(SQLException.class::isInstance)
                .map(SQLException.class::cast)
                .map(SQLException::getSQLState)
                .filter(Objects::nonNull)
                .anyMatch(
                        state ->
                                state.startsWith("23")
                                        || state.equals("40001")
                                        || state.equals("40P01"));
    }
}
