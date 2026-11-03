package com.kartaguez.pocoma.infra.persistence.read.jdbc.v3;

import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.TransactionDefinition;
import javax.sql.DataSource;
import java.util.*;
import java.util.function.Supplier;

/** Shared SQL/owner mechanics only; no projection calculation, lifecycle or progression. */
final class ReadJdbc {
    final DataSource source; final JdbcTemplate sql;
    ReadJdbc(DataSource source, SpringAttemptTransactions owner) {
        this.source=Objects.requireNonNull(source); owner.requireSource(source); sql=new JdbcTemplate(source);
    }
    <T> T read(RecoveryReadContext context,Supplier<T> action) { return SpringAttemptTransactions.observed(source,context,action); }
    <T> T readOwned(Supplier<T> action) {
        return SpringAttemptTransactions.participating(source,() -> {
            if(!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.hasResource(source))
                throw new IllegalStateException("Caller READ transaction required");
            return action.get();
        });
    }
    <T> T write(Supplier<T> action) {
        return SpringAttemptTransactions.guarded(source,() -> {
            if(!Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),TransactionDefinition.ISOLATION_READ_COMMITTED))
                throw new IllegalStateException("READ writer requires caller READ_COMMITTED");
            return action.get();
        });
    }
    static void same(Object expected,Object actual) { if(!Objects.equals(expected,actual)) throw new IllegalStateException("Divergent or incomplete READ content"); }
}
