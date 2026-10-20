package com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.function.Supplier;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.kartaguez.pocoma.port.transaction.EventRef;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;

/** PRIMARY publication protocol. The owner resolves the template; nested invocations
 * are participants only. Composition exposes the narrow application declaration facade;
 * writers guard their writes but never declare publications themselves.
 */
public final class PrimaryEventTransactionRunner implements TransactionRunner {
    private record ResourceKey(JpaTransactionManager manager, EntityManagerFactory emf) {}
    private enum Phase { OPEN, CLOSED, POISONED, CLEANED }

    private final JpaTransactionManager manager;
    private final EntityManagerFactory emf;
    private final ResourceKey key;
    private final TransactionTemplate template;
    private final PrimaryEventTransactionSuffix suffix;

    public PrimaryEventTransactionRunner(JpaTransactionManager manager, PrimaryEventTransactionSuffix suffix) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.emf = Objects.requireNonNull(manager.getEntityManagerFactory(), "PRIMARY emf");
        Objects.requireNonNull(manager.getDataSource(), "PRIMARY datasource");
        if (manager.isNestedTransactionAllowed())
            throw new IllegalArgumentException("PRIMARY event protocol forbids nested transactions");
        this.key = new ResourceKey(manager, emf);
        this.template = new TransactionTemplate(manager);
        this.suffix = Objects.requireNonNull(suffix, "suffix");
    }

    @Override public <T> T runInTransaction(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        Batch existing = current();
        if (existing != null) {
            requireOpen();
            try { return action.get(); }
            catch (RuntimeException | Error failure) { existing.poison(); throw failure; }
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive())
            throw violation("Unknown ambient transaction or synchronization");

        Batch[] installed = new Batch[1];
        int[] completion = { TransactionSynchronization.STATUS_UNKNOWN };
        boolean[] callbackCompleted = { false };
        try {
            T result = template.execute(status -> {
                if (!status.isNewTransaction()) throw violation("Owner requires a new physical transaction");
                Batch batch = new Batch(status);
                installed[0] = batch;
                // Register cleanup before exposing the batch, including installation failures.
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int outcome) {
                        completion[0] = outcome;
                        batch.clean();
                    }
                });
                TransactionSynchronizationManager.bindResource(key, batch);
                requireOpen();
                T value = action.get(); // Includes all S/Claim finalization, before the suffix.
                batch.requireCommittable();
                batch.entityManager.getEntityManager().flush();
                batch.requireCommittable(); // Successful flush confirms deferred insert intentions.
                ClosedPublishedEvents closed = batch.close();
                batch.requireCommittable();
                if (!closed.isEmpty()) suffix.execute(closed);
                batch.requireCommittable(); // A caught guard failure inside a suffix still poisons.
                callbackCompleted[0] = true; // No action/flush/suffix failure after this point.
                return value;
            });
            if (completion[0] != TransactionSynchronization.STATUS_COMMITTED)
                throw new IllegalStateException("PRIMARY commit unconfirmed");
            return result;
        } catch (RuntimeException failure) {
            // Hibernate may report ROLLED_BACK after a JDBC COMMIT IOException, even
            // when PostgreSQL already committed. Never infer durable rollback from it.
            if (callbackCompleted[0] && completion[0] != TransactionSynchronization.STATUS_COMMITTED
                    && hasTransportFailure(failure))
                throw new com.kartaguez.pocoma.port.transaction.TransactionCommitUnknownException(failure);
            throw failure;
        } finally {
            // Fallback for installation/resolution failures; never changes completion evidence.
            if (installed[0] != null) installed[0].clean();
        }
    }

    private static boolean hasTransportFailure(Throwable failure) {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        var pending = new java.util.ArrayDeque<Throwable>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) continue;
            if (current instanceof java.sql.SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("08") || state.equals("40003"))) return true;
                if (sql.getNextException() != null) pending.add(sql.getNextException());
            }
            if (current.getCause() != null) pending.add(current.getCause());
        }
        return false;
    }

    /** Writer guard, before accepting a durable publication. Does not collect anything. */
    public void requireOpen() {
        Batch batch = current();
        if (batch == null || !batch.samePhysicalTransaction() || batch.phase != Phase.OPEN)
            throw violation("Publication requires the current OPEN PRIMARY owner");
        batch.requireCommittable();
    }

    /** Infrastructure-only body guard, also effective for writers constructed by new.
     * The resource is the writer's actual datasource or EMF, never a fallback connection.
     * Any failure irreversibly dooms the physical owner even when caught by its caller.
     */
    public static <T> T guardedWrite(Object persistenceResource, Supplier<T> action) {
        var batches = TransactionSynchronizationManager.getResourceMap().values().stream()
                .filter(Batch.class::isInstance).map(Batch.class::cast).distinct().toList();
        var matches = batches.stream().filter(batch -> batch.owns(persistenceResource)).toList();
        if (matches.size() != 1) {
            batches.forEach(Batch::poison);
            TransactionSynchronizationManager.getResourceMap().values().forEach(resource -> {
                if (resource instanceof ConnectionHolder holder) holder.setRollbackOnly();
                if (resource instanceof EntityManagerHolder holder
                        && holder.getEntityManager().getTransaction().isActive())
                    holder.getEntityManager().getTransaction().setRollbackOnly();
            });
            throw new IllegalTransactionStateException("Durable writer requires its OPEN PRIMARY owner");
        }
        Batch batch = matches.getFirst();
        try {
            batch.requireWritable();
            T value = action.get();
            batch.requireWritable();
            return value;
        } catch (RuntimeException | Error failure) {
            batch.poison();
            throw failure;
        }
    }

    /** Called by orchestration through its injected narrow facade, only after acceptance. */
    public void recordPublishedEvent(EventRef event) {
        requireOpen();
        Batch batch = current();
        if (event == null || !batch.identities.add(event))
            throw violation("Null or duplicate accepted publication");
        batch.events.add(event);
    }

    @Override public void runAfterCommit(Runnable observation) {
        Objects.requireNonNull(observation, "observation");
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { observation.run(); return; }
        requireOpen();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { observation.run(); }
        });
    }

    EntityManagerFactory entityManagerFactory() { return emf; }

    void poisonPublication() {
        Batch batch = current();
        if (batch != null) batch.poison();
    }

    private Batch current() { return (Batch) TransactionSynchronizationManager.getResource(key); }

    private IllegalTransactionStateException violation(String message) {
        Batch batch = current();
        if (batch != null) batch.poison();
        // A direct guard/declaration under an unknown PRIMARY template must also doom it,
        // even when its caller catches the exception. Never create a fallback EntityManager.
        Object resource = TransactionSynchronizationManager.getResource(emf);
        if (resource instanceof EntityManagerHolder holder && holder.getEntityManager().getTransaction().isActive())
            holder.getEntityManager().getTransaction().setRollbackOnly();
        return new IllegalTransactionStateException(message);
    }

    private final class Batch {
        private final TransactionStatus status;
        private final EntityManagerHolder entityManager;
        private final Object connection;
        private final ArrayList<EventRef> events = new ArrayList<>();
        private final HashSet<EventRef> identities = new HashSet<>();
        private Phase phase = Phase.OPEN;

        Batch(TransactionStatus status) {
            this.status = status;
            Object resource = TransactionSynchronizationManager.getResource(emf);
            if (!(resource instanceof EntityManagerHolder holder))
                throw violation("Missing PRIMARY EntityManager");
            entityManager = holder;
            connection = TransactionSynchronizationManager.getResource(manager.getDataSource());
        }
        boolean owns(Object resource) { return resource != null && (resource == emf || resource == manager.getDataSource()); }
        void requireWritable() { PrimaryEventTransactionRunner.this.requireOpen(); }
        boolean samePhysicalTransaction() {
            return TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.isSynchronizationActive()
                    && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    && entityManager.getEntityManager().getTransaction().isActive()
                    && TransactionSynchronizationManager.getResource(emf) == entityManager
                    && connection instanceof ConnectionHolder
                    && TransactionSynchronizationManager.getResource(manager.getDataSource()) == connection;
        }
        void requireCommittable() {
            if (!samePhysicalTransaction()) throw violation("PRIMARY transaction identity changed");
            if (phase == Phase.POISONED || phase == Phase.CLEANED || status.isRollbackOnly()) {
                poison();
                throw new UnexpectedRollbackException("PRIMARY publication batch is irreversibly poisoned/rollback-only");
            }
        }
        void poison() {
            if (phase != Phase.CLEANED) { phase = Phase.POISONED; status.setRollbackOnly(); }
        }
        ClosedPublishedEvents close() {
            requireOpen();
            var snapshot = new ClosedPublishedEvents(events);
            phase = Phase.CLOSED;
            return snapshot;
        }
        void clean() {
            if (TransactionSynchronizationManager.getResource(key) == this)
                TransactionSynchronizationManager.unbindResourceIfPossible(key);
            events.clear();
            identities.clear();
            phase = Phase.CLEANED;
        }
    }
}
