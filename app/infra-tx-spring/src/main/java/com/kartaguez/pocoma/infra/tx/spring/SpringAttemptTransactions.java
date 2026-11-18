package com.kartaguez.pocoma.infra.tx.spring;

import com.kartaguez.pocoma.port.transaction.*;

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

    private record AttemptBinding(DataSource source, EntityManagerFactory emf, boolean read) {}

    private static final class AttemptOutcome {
        final Guard guard;
        int completion = TransactionSynchronization.STATUS_UNKNOWN;
        boolean handedToCommit;
        Throwable callbackFailure;
        Unit capturedUnit;

        AttemptOutcome(Guard guard) {
            this.guard = guard;
        }

        Throwable originalFailure(RuntimeException failure) {
            if (callbackFailure != null) return callbackFailure;
            if (capturedUnit != null && capturedUnit.poisonCause != null) {
                return capturedUnit.poisonCause;
            }
            return failure;
        }
    }

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
        volatile Throwable poisonCause;
        volatile boolean resolved;
        final Guard guard;

        Unit(
                TransactionStatus status,
                AttemptBinding binding,
                TransactionSynchronization synchronization,
                Guard guard) {
            this.status = status;
            this.guard = Objects.requireNonNull(guard);
            this.source = binding.source();
            this.emf = binding.emf();
            this.read = binding.read();
            this.synchronization = synchronization;
            connection = TransactionSynchronizationManager.getResource(source);
            entityManager = emf == null ? null : TransactionSynchronizationManager.getResource(emf);
        }

        void invalidate(Throwable cause) {
            synchronized (this) {
                if (poisonCause == null) poisonCause = cause;
            }
            guard.invalidate(cause);
            // A foreign thread can invalidate the pure guard, never drive this manager.
            if (canDriveRollback()) {
                status.setRollbackOnly();
                poisonEntityManager(entityManager, cause);
            }
        }

        private boolean canDriveRollback() {
            if (Thread.currentThread() != thread || resolved) return false;
            return !status.isCompleted();
        }

        void requireCurrent() {
            try {
                guard.requireValid();
                if (poisonCause != null) rethrow(poisonCause);
                checkCurrent();
            } catch (RuntimeException | Error failure) {
                invalidate(failure);
                poisonUnits(failure);
                throw failure;
            }
        }

        private void checkCurrent() {
            if (!isCurrentUnitValid()) {
                throw new IllegalTransactionStateException(
                        "Attempt unit changed, expired or poisoned", poisonCause);
            }
        }

        private boolean isCurrentUnitValid() {
            if (!isActiveOnOwnerThread()) return false;
            if (!hasActiveSpringSynchronization()) return false;
            if (!hasBoundJdbcResources()) return false;
            return hasValidEntityManager();
        }

        private boolean isActiveOnOwnerThread() {
            if (!active || Thread.currentThread() != thread) return false;
            return !status.isRollbackOnly();
        }

        private boolean hasActiveSpringSynchronization() {
            // A second manager can reuse the JPA JDBC holder yet start/commit its own
            // transaction. Spring suspends the owner's synchronizations even in that case.
            if (!TransactionSynchronizationManager.isActualTransactionActive()) return false;
            if (!TransactionSynchronizationManager.isSynchronizationActive()) return false;
            return TransactionSynchronizationManager.getSynchronizations().contains(synchronization);
        }

        private boolean hasBoundJdbcResources() {
            if (!(connection instanceof ConnectionHolder)) return false;
            if (TransactionSynchronizationManager.getResource(new Key(source)) != this) return false;
            return TransactionSynchronizationManager.getResource(source) == connection;
        }

        private boolean hasValidEntityManager() {
            if (emf == null) return true;
            if (!(entityManager instanceof EntityManagerHolder)) return false;
            return TransactionSynchronizationManager.getResource(emf) == entityManager;
        }

        void open() {
            requireCurrent();
            if (isWriteDisallowed()) {
                throw new IllegalTransactionStateException(
                        "Attempt write publication closed or read-only");
            }
        }

        private boolean isWriteDisallowed() {
            if (read || publicationClosed) return true;
            return TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        }

        @Override
        public void requireActiveRead() {
            try {
                requireCurrent();
                if (!isRepeatableReadSnapshot()) {
                    throw new IllegalTransactionStateException(
                            "Recovery requires its repeatable read snapshot");
                }
            } catch (RuntimeException | Error failure) {
                invalidate(failure);
                poisonUnits(failure);
                throw failure;
            }
        }

        private boolean isRepeatableReadSnapshot() {
            if (!read || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                return false;
            }
            return Objects.equals(
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                    TransactionDefinition.ISOLATION_REPEATABLE_READ);
        }
    }

    /**
     * Spring checks rollback flags before invoking transaction synchronizations. JDBC commit
     * itself does not honour a ConnectionHolder flag raised by a caught late callback.
     * This stateless check runs after those callbacks, immediately before manager.doCommit.
     * Transactions without an Attempt Unit keep their existing behaviour.
     */
    private static final class PoisonedCommitGuard implements TransactionExecutionListener {
        @Override
        public void beforeCommit(TransactionExecution transaction) {
            TransactionSynchronizationManager.getResourceMap().values().stream()
                    .filter(Unit.class::isInstance)
                    .map(Unit.class::cast)
                    .filter(unit -> unit.status == transaction)
                    .forEach(
                            unit -> {
                                unit.guard.requireValid();
                                if (unit.poisonCause != null) rethrow(unit.poisonCause);
                                if (unit.status.isRollbackOnly())
                                    throw new IllegalTransactionStateException(
                                            "Poisoned attempt cannot reach physical commit",
                                            unit.poisonCause);
                            });
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
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("Positive attempt timeout required");
        }
        this.emf = resolveEntityManagerFactory(source, manager);
        configureTransactionManager((AbstractPlatformTransactionManager) manager);
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

    private static EntityManagerFactory resolveEntityManagerFactory(
            DataSource source, PlatformTransactionManager manager) {
        if (manager instanceof JpaTransactionManager jpa && isDirectAuthority(jpa, source)) {
            return requireJpaFactory(jpa, source);
        }
        if (manager instanceof DataSourceTransactionManager jdbc && isDirectAuthority(jdbc, source)) {
            return null;
        }
        throw new IllegalArgumentException(
                "One authority manager/source required; nesting forbidden");
    }

    private static EntityManagerFactory requireJpaFactory(
            JpaTransactionManager jpa, DataSource source) {
        var factory = Objects.requireNonNull(jpa.getEntityManagerFactory());
        if (!(factory instanceof EntityManagerFactoryInfo info) || info.getDataSource() != source) {
            throw new IllegalArgumentException(
                    "JPA factory must use the identical authority datasource");
        }
        return factory;
    }

    private static boolean isDirectAuthority(JpaTransactionManager jpa, DataSource source) {
        return jpa.getDataSource() == source && !jpa.isNestedTransactionAllowed();
    }

    private static boolean isDirectAuthority(
            DataSourceTransactionManager jdbc, DataSource source) {
        return jdbc.getDataSource() == source && !jdbc.isNestedTransactionAllowed();
    }

    private static void configureTransactionManager(AbstractPlatformTransactionManager spring) {
        spring.setRollbackOnCommitFailure(true);
        spring.setGlobalRollbackOnParticipationFailure(true);
        if (spring.getTransactionExecutionListeners().stream()
                .noneMatch(PoisonedCommitGuard.class::isInstance)) {
            spring.addListener(new PoisonedCommitGuard());
        }
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
    public <T> T read(Function<RecoveryReadContext, T> action, Guard guard) {
        return run(
                read,
                true,
                guard,
                unit -> {
                    new JdbcTemplate(source).execute("set transaction read only");
                    unit.requireActiveRead();
                    return action.apply(unit);
                });
    }

    @Override
    public <T> T write(Supplier<T> action, Guard guard) {
        return run(write, false, guard, unit -> action.get());
    }

    private <T> T run(
            TransactionTemplate template, boolean reading, Guard guard, Function<Unit, T> action) {
        Objects.requireNonNull(guard).requireValid();
        Objects.requireNonNull(action);
        requireNoAmbientTransaction(guard);
        var outcome = new AttemptOutcome(guard);
        var binding = new AttemptBinding(source, emf, reading);
        try {
            if (TransactionSynchronizationManager.getResource(source) != null) {
                throw new IllegalTransactionStateException(
                        "Attempt owner requires no retained JDBC holder");
            }
            T result = executeInTemplate(template, binding, action, outcome);
            if (outcome.completion != TransactionSynchronization.STATUS_COMMITTED) {
                throw new Failure(
                        Resolution.UNKNOWN, new IllegalStateException("Commit unconfirmed"));
            }
            return result;
        } catch (RuntimeException failure) {
            throw translateFailure(failure, outcome, guard);
        } finally {
            TransactionSynchronizationManager.unbindResourceIfPossible(new Key(source));
        }
    }

    private static void requireNoAmbientTransaction(Guard guard) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        var failure =
                new IllegalTransactionStateException(
                        "Attempt owner requires no ambient transaction");
        guard.invalidate(failure);
        poisonUnits(failure);
        throw failure;
    }

    private <T> T executeInTemplate(
            TransactionTemplate template,
            AttemptBinding binding,
            Function<Unit, T> action,
            AttemptOutcome outcome) {
        return template.execute(
                status -> {
                    var synchronization = createSynchronization(outcome);
                    var unit = new Unit(status, binding, synchronization, outcome.guard);
                    outcome.capturedUnit = unit;
                    TransactionSynchronizationManager.bindResource(new Key(source), unit);
                    TransactionSynchronizationManager.registerSynchronization(synchronization);
                    return invokeWithinUnit(unit, action, outcome);
                });
    }

    private static TransactionSynchronization createSynchronization(AttemptOutcome outcome) {
        return new TransactionSynchronization() {
            @Override
            public void afterCompletion(int completionStatus) {
                outcome.completion = completionStatus;
                if (outcome.capturedUnit != null) {
                    outcome.capturedUnit.resolved = true;
                }
            }
        };
    }

    private static <T> T invokeWithinUnit(
            Unit unit, Function<Unit, T> action, AttemptOutcome outcome) {
        try {
            unit.requireCurrent();
            T value = action.apply(unit);
            unit.requireCurrent();
            outcome.handedToCommit = true;
            return value;
        } catch (RuntimeException | Error failure) {
            outcome.callbackFailure = failure;
            unit.invalidate(failure);
            throw failure;
        } finally {
            // Keep the guard bound through Spring callbacks and physical termination.
            // No writer or recovery context may re-enter after this callback returns.
            unit.active = false;
        }
    }

    private static Failure translateFailure(
            RuntimeException failure, AttemptOutcome outcome, Guard guard) {
        Throwable original = outcome.originalFailure(failure);
        if (original != failure) original.addSuppressed(failure);
        var resolution = resolveOutcome(failure, original, outcome);
        guard.invalidate(original);
        return new Failure(resolution, original);
    }

    private static Resolution resolveOutcome(
            RuntimeException failure, Throwable original, AttemptOutcome outcome) {
        if (outcome.completion == TransactionSynchronization.STATUS_COMMITTED) {
            return Resolution.COMMITTED;
        }
        if (isConfirmedRollback(failure, original, outcome)) {
            return Resolution.ROLLED_BACK;
        }
        return Resolution.UNKNOWN;
    }

    private static boolean isConfirmedRollback(
            RuntimeException failure, Throwable original, AttemptOutcome outcome) {
        if (outcome.completion != TransactionSynchronization.STATUS_ROLLED_BACK) return false;
        if (transportFailure(failure) || transportFailure(original)) return false;
        return !outcome.handedToCommit || explicitServerRejection(failure);
    }

    @Override
    public void poison(Throwable cause) {
        Objects.requireNonNull(cause);
        poisonUnits(cause);
        poisonConnection(source, cause);
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
            poisonConnection(source, failure);
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
            poisonConnection(source, failure);
            throw failure;
        }
    }

    /** The entire observation, including decoding and comparisons, participates in poison. */
    public static <T> T observed(
            DataSource source, RecoveryReadContext context, Supplier<T> action) {
        try {
            requireRead(source, context);
            return action.get();
        } catch (RuntimeException | Error failure) {
            poisonUnits(failure);
            poisonConnection(source, failure);
            throw failure;
        }
    }

    /** Existing autonomous L1 preconditions remain those of the wrapped reader. */
    public static <T> T participating(DataSource source, Supplier<T> action) {
        try {
            var unit = current(source);
            if (unit != null) unit.requireCurrent();
            else if (TransactionSynchronizationManager.getResourceMap().values().stream()
                    .anyMatch(Unit.class::isInstance))
                throw new IllegalTransactionStateException("Reader belongs to another authority");
            return action.get();
        } catch (RuntimeException | Error failure) {
            poisonUnits(failure);
            poisonConnection(source, failure);
            throw failure;
        }
    }

    public static void requireRead(DataSource source, RecoveryReadContext context) {
        try {
            Objects.requireNonNull(context).requireActiveRead();
            var unit = current(source);
            if (unit == null || unit != context)
                throw new IllegalTransactionStateException(
                        "Observation belongs to another authority");
        } catch (RuntimeException | Error failure) {
            // An expired Unit retains its original Guard, even on a foreign caller thread.
            if (context instanceof Unit unit) unit.invalidate(failure);
            poisonUnits(failure);
            poisonConnection(source, failure);
            throw failure;
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        throw new IllegalStateException("Unexpected checked attempt failure", failure);
    }

    private static Unit current(DataSource source) {
        return (Unit) TransactionSynchronizationManager.getResource(new Key(source));
    }

    private static void poisonUnits(Throwable cause) {
        TransactionSynchronizationManager.getResourceMap().values().stream()
                .filter(Unit.class::isInstance)
                .map(Unit.class::cast)
                .forEach(unit -> unit.invalidate(cause));
    }

    private static void poisonEntityManager(Object resource, Throwable cause) {
        try {
            if (resource instanceof EntityManagerHolder holder) {
                var transaction = holder.getEntityManager().getTransaction();
                if (transaction.isActive()) transaction.setRollbackOnly();
            }
        } catch (RuntimeException markingFailure) {
            if (markingFailure != cause) cause.addSuppressed(markingFailure);
        }
    }

    private static void poisonConnection(DataSource source, Throwable cause) {
        if (TransactionSynchronizationManager.getResource(source)
                instanceof ConnectionHolder holder) holder.setRollbackOnly();
        // Autonomous L1-B can use a caller-owned JPA transaction, without an Engine Unit.
        // JpaTransactionManager reads the EntityTransaction flag, not the JDBC holder flag.
        TransactionSynchronizationManager.getResourceMap()
                .forEach(
                        (key, resource) -> {
                            if (key instanceof EntityManagerFactoryInfo info
                                    && info.getDataSource() == source)
                                poisonEntityManager(resource, cause);
                        });
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
