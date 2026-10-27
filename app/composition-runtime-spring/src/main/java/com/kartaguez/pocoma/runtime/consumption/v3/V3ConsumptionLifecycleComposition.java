package com.kartaguez.pocoma.runtime.consumption.v3;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.AttemptFailureClassifier;
import com.kartaguez.pocoma.engine.consumption.v3.port.AttemptFailureClassifier.FailureKind;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.*;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CancellationException;

import javax.sql.DataSource;

/** Explicit construction only. Pool sizing is a prerequisite, NOT a connection reservation. */
public final class V3ConsumptionLifecycleComposition {
    private V3ConsumptionLifecycleComposition() {}

    public static ConsumptionEngine assemble(
            V3BootstrapGuard guard,
            ConsumptionDefinition definition,
            DataSource source,
            AttemptTransactions transactions,
            ConsumptionParticipant participant,
            LifecycleSettings settings,
            WorkerCapacity capacity,
            int maximumConnections,
            AttemptFailureClassifier classifier) {
        Objects.requireNonNull(guard).validateLifecyclePrivileges();
        guard.validateLifecycleSource(source, definition.authority());
        if (source instanceof com.zaxxer.hikari.HikariDataSource pool
                && maximumConnections != pool.getMaximumPoolSize())
            throw new IllegalArgumentException(
                    "declared capacity differs from the actual Hikari pool");
        if (maximumConnections < capacity.workerLimit() + 4)
            throw new IllegalArgumentException(
                    "M_W + control + two claim renewals + lease access required");
        return new ConsumptionEngine(
                definition,
                transactions,
                new JdbcConsumptionSlotStore(source),
                participant,
                new JdbcPartitionOwnershipStore(source),
                settings,
                capacity,
                classifier);
    }

    public static FailureKind classify(Throwable failure) {
        if (failure == null) return FailureKind.NON_COUNTABLE;
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var queue = new ArrayDeque<Throwable>();
        queue.add(failure);
        boolean sql = false, technical = false, excluded = false;
        while (!queue.isEmpty()) {
            var cause = queue.removeFirst();
            if (!seen.add(cause)) continue;
            if (cause.getCause() != null) queue.addLast(cause.getCause());
            Collections.addAll(queue, cause.getSuppressed());
            if (cause instanceof SQLException jdbc) {
                if (jdbc.getNextException() != null) queue.addLast(jdbc.getNextException());
                var state = jdbc.getSQLState();
                if (state != null && (state.startsWith("08") || state.equals("40003")))
                    excluded = true;
                else sql = true;
            } else if (cause
                            instanceof
                            org.springframework.transaction.IllegalTransactionStateException
                    || cause instanceof org.springframework.beans.BeansException) excluded = true;
            else if (cause instanceof CancellationException
                    || cause instanceof InterruptedException
                    || cause instanceof org.springframework.transaction.UnexpectedRollbackException
                    || cause instanceof org.springframework.transaction.TransactionSystemException
                    || cause instanceof AttemptTransactions.Failure) {
                // A wrapper/cancellation alone establishes no countable cause.
            } else if (cause instanceof org.springframework.dao.DataAccessException
                    || cause instanceof jakarta.persistence.PersistenceException) sql = true;
            else if (cause instanceof RuntimeException || cause instanceof Error) technical = true;
        }
        return excluded
                ? FailureKind.NON_COUNTABLE
                : sql
                        ? FailureKind.SQL_OR_JPA
                        : technical ? FailureKind.TECHNICAL : FailureKind.NON_COUNTABLE;
    }
}
