package com.kartaguez.pocoma.infra.tx.spring;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier.FailureKind;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.port.transaction.AttemptTransactions;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
/** Classifies physical causes; the L2 owner alone decides the protocol response. */
public final class ConsumptionFailureClassifier {
private ConsumptionFailureClassifier() {}
    /** Excluded causes dominate SQL/JPA, then technical faults; wrappers alone prove no fault. */
    public static FailureKind classify(Throwable failure) {
        if (failure == null) return FailureKind.NON_COUNTABLE;
        var causes = collectCauses(failure);
        if (causes.stream().anyMatch(ConsumptionFailureClassifier::isExplicitExclusion)) {
            return FailureKind.NON_COUNTABLE;
        }
        if (causes.stream().anyMatch(ConsumptionFailureClassifier::isSqlOrJpaCause)) {
            return FailureKind.SQL_OR_JPA;
        }
        if (causes.stream().anyMatch(ConsumptionFailureClassifier::isTechnicalCause)) {
            return FailureKind.TECHNICAL;
        }
        return FailureKind.NON_COUNTABLE;
    }

    private static boolean isTechnicalCause(Throwable cause) {
        return !isWrapperCause(cause)
                && (cause instanceof RuntimeException || cause instanceof Error);
    }

    private static List<Throwable> collectCauses(Throwable root) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var queue = new ArrayDeque<Throwable>();
        var collected = new ArrayList<Throwable>();
        queue.add(root);
        while (!queue.isEmpty()) {
            var cause = queue.removeFirst();
            if (!seen.add(cause)) continue;
            collected.add(cause);
            enqueueNextCauses(cause, queue);
        }
        return collected;
    }

    private static void enqueueNextCauses(Throwable cause, Deque<Throwable> queue) {
        if (cause.getCause() != null) queue.addLast(cause.getCause());
        Collections.addAll(queue, cause.getSuppressed());
        if (cause instanceof org.springframework.transaction.TransactionSystemException system
                && system.getOriginalException() != null) {
            queue.addLast(system.getOriginalException());
        }
        if (cause instanceof SQLException jdbc && jdbc.getNextException() != null) {
            queue.addLast(jdbc.getNextException());
        }
    }

    private static final List<Class<? extends Throwable>> EXPLICIT_EXCLUSION_TYPES =
            List.of(
                    PreparationReads.Invalid.class,
                    org.springframework.transaction.IllegalTransactionStateException.class,
                    org.springframework.beans.BeansException.class);

    private static final List<Class<? extends Throwable>> WRAPPER_TYPES =
            List.of(
                    CancellationException.class,
                    InterruptedException.class,
                    CompletionException.class,
                    ExecutionException.class,
                    org.springframework.transaction.UnexpectedRollbackException.class,
                    org.springframework.transaction.TransactionSystemException.class,
                    AttemptTransactions.Failure.class);

    private static final List<Class<? extends Throwable>> SQL_OR_JPA_TYPES =
            List.of(
                    SQLException.class,
                    org.springframework.dao.DataAccessException.class,
                    jakarta.persistence.PersistenceException.class);

    private static boolean isInstanceOfAny(
            Throwable cause, List<Class<? extends Throwable>> types) {
        return types.stream().anyMatch(type -> type.isInstance(cause));
    }

    private static boolean isExplicitExclusion(Throwable cause) {
        if (cause instanceof SQLException jdbc) {
            var state = jdbc.getSQLState();
            return state != null && (state.startsWith("08") || state.equals("40003"));
        }
        return isInstanceOfAny(cause, EXPLICIT_EXCLUSION_TYPES);
    }

    private static boolean isWrapperCause(Throwable cause) {
        return isInstanceOfAny(cause, WRAPPER_TYPES);
    }

    private static boolean isSqlOrJpaCause(Throwable cause) {
        return isInstanceOfAny(cause, SQL_OR_JPA_TYPES);
    }
}
