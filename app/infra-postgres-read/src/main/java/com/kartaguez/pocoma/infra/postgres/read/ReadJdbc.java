package com.kartaguez.pocoma.infra.postgres.read;

import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shared JDBC access to the caller's READ transaction. */
final class ReadJdbc {
    final DataSource source;
    final JdbcTemplate sql;

    ReadJdbc(DataSource source, SpringAttemptTransactions owner) {
        this(source);
        owner.requireSource(source);
    }

    ReadJdbc(DataSource source) {
        this.source = Objects.requireNonNull(source);
        sql = new JdbcTemplate(source);
    }

    <T> T participate(Supplier<T> action) {
        return SpringAttemptTransactions.participating(source, action);
    }

    <T> T read(RecoveryReadContext context, Supplier<T> action) {
        return SpringAttemptTransactions.observed(source, context, action);
    }

    <T> T readOwned(Supplier<T> action) {
        return participate(() -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.hasResource(source)) {
                throw new IllegalStateException("Caller READ transaction required");
            }
            return action.get();
        });
    }

    <T> T write(Supplier<T> action) {
        return SpringAttemptTransactions.guarded(source, () -> {
            if (!Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                    TransactionDefinition.ISOLATION_READ_COMMITTED)) {
                throw new IllegalStateException("READ writer requires caller READ_COMMITTED");
            }
            return action.get();
        });
    }

    static void same(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException("Divergent or incomplete READ content");
        }
    }
}
