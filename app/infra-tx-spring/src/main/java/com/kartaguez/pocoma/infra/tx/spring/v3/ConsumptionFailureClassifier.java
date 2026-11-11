package com.kartaguez.pocoma.infra.tx.spring.v3;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier.FailureKind;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
/** Classifies physical causes; the L2 owner alone decides the protocol response. */
public final class ConsumptionFailureClassifier {
private ConsumptionFailureClassifier() {}
    /** Excluded causes dominate SQL/JPA, then technical faults; wrappers alone prove no fault. */
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
            if (cause instanceof org.springframework.transaction.TransactionSystemException system
                    && system.getOriginalException() != null)
                queue.addLast(system.getOriginalException());
            if (cause instanceof SQLException jdbc) {
                if (jdbc.getNextException() != null) queue.addLast(jdbc.getNextException());
                var state = jdbc.getSQLState();
                if (state != null && (state.startsWith("08") || state.equals("40003")))
                    excluded = true;
                else sql = true;
            } else if (cause instanceof PreparationReads.Invalid) excluded = true;
            else if (cause
                            instanceof
                            org.springframework.transaction.IllegalTransactionStateException
                    || cause instanceof org.springframework.beans.BeansException) excluded = true;
            else if (cause instanceof CancellationException
                    || cause instanceof InterruptedException
                    || cause instanceof CompletionException
                    || cause instanceof ExecutionException
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
