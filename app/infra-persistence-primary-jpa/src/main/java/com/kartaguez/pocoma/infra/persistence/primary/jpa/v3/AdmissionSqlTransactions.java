package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import com.kartaguez.pocoma.contract.writeadmission.AdmissionFailure;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.*;

import org.postgresql.PGConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;

import java.time.Duration;
import java.util.function.*;

import javax.sql.DataSource;

/**
 * Admission-local physical observation, without changing the shared transaction owner. PID capture
 * is driver-local (no SQL on successful calls). Unknown termination alone triggers an independent
 * bounded observation after Spring has released its connection.
 */
public final class AdmissionSqlTransactions implements AttemptTransactions {
    private final DataSource source;
    private final SpringAttemptTransactions delegate;

    public AdmissionSqlTransactions(DataSource source, SpringAttemptTransactions delegate) {
        this.source = source;
        this.delegate = delegate;
        delegate.requireSource(source);
    }

    private void capture(int[] pid) {
        var connection = DataSourceUtils.getConnection(source);
        try {
            pid[0] = connection.unwrap(PGConnection.class).getBackendPID();
        } catch (java.sql.SQLException unavailable) {
            throw new IllegalStateException("Admission backend observation unavailable");
        } finally {
            DataSourceUtils.releaseConnection(connection, source);
        }
    }

    private <T> T observe(int[] pid, Supplier<T> action) {
        try {
            return action.get();
        } catch (AttemptTransactions.Failure failure) {
            if (failure.resolution() == Resolution.UNKNOWN && !terminated(pid[0]))
                throw new AdmissionFailure(503, false);
            throw failure;
        }
    }

    private boolean terminated(int pid) {
        if (pid <= 0) return false;
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        var observer = new JdbcTemplate(source);
        observer.setQueryTimeout(1);
        do {
            try {
                // Our observer reusing the same physical backend proves that its former unit ended.
                if (Boolean.TRUE.equals(
                        observer.queryForObject(
                                "select not exists(select 1 from pg_stat_activity where pid=? and"
                                        + " pid<>pg_backend_pid() and xact_start is not null)",
                                Boolean.class,
                                pid))) return true;
            } catch (RuntimeException unavailable) {
                return false;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.nanoTime() < deadline);
        return false;
    }

    @Override
    public <T> T read(Function<RecoveryReadContext, T> action, Guard guard) {
        int[] pid = {0};
        return observe(
                pid,
                () ->
                        delegate.read(
                                c -> {
                                    capture(pid);
                                    return action.apply(c);
                                },
                                guard));
    }

    @Override
    public <T> T write(Supplier<T> action, Guard guard) {
        int[] pid = {0};
        return observe(
                pid,
                () ->
                        delegate.write(
                                () -> {
                                    capture(pid);
                                    return action.get();
                                },
                                guard));
    }

    @Override
    public void poison(Throwable failure) {
        delegate.poison(failure);
    }

    @Override
    public void requirePublicationClosed() {
        delegate.requirePublicationClosed();
    }
}
