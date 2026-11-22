package com.kartaguez.pocoma.infra.tx.spring;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.port.transaction.AttemptTransactions;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Verifies post-COMMIT semantics and fault isolation of {@link
 * SpringAttemptTransactions#afterCommit(DataSource, Runnable)}.
 */
final class SpringAttemptTransactionsAfterCommitTest {

    private static final class StubJdbcEnv {
        final List<String> events = new CopyOnWriteArrayList<>();
        final AtomicBoolean autoCommit = new AtomicBoolean(true);
        final DataSource dataSource;
        final SpringAttemptTransactions tx;

        StubJdbcEnv() {
            Connection conn =
                    (Connection)
                            Proxy.newProxyInstance(
                                    Connection.class.getClassLoader(),
                                    new Class<?>[] {Connection.class},
                                    (proxy, method, args) -> {
                                        switch (method.getName()) {
                                            case "getAutoCommit" -> {
                                                return autoCommit.get();
                                            }
                                            case "setAutoCommit" -> {
                                                autoCommit.set((Boolean) args[0]);
                                                return null;
                                            }
                                            case "getTransactionIsolation" -> {
                                                return Connection.TRANSACTION_READ_COMMITTED;
                                            }
                                            case "setTransactionIsolation",
                                                    "setReadOnly",
                                                    "clearWarnings",
                                                    "close" -> {
                                                return null;
                                            }
                                            case "isReadOnly", "isClosed" -> {
                                                return false;
                                            }
                                            case "commit" -> {
                                                events.add("PHYSICAL_COMMIT");
                                                return null;
                                            }
                                            case "rollback" -> {
                                                events.add("PHYSICAL_ROLLBACK");
                                                return null;
                                            }
                                            case "hashCode" -> {
                                                return System.identityHashCode(proxy);
                                            }
                                            case "equals" -> {
                                                return proxy == args[0];
                                            }
                                            case "toString" -> {
                                                return "StubConnection";
                                            }
                                            default -> {
                                                return null;
                                            }
                                        }
                                    });
            this.dataSource =
                    (DataSource)
                            Proxy.newProxyInstance(
                                    DataSource.class.getClassLoader(),
                                    new Class<?>[] {DataSource.class},
                                    (proxy, method, args) -> {
                                        return switch (method.getName()) {
                                            case "getConnection" -> conn;
                                            case "hashCode" -> System.identityHashCode(proxy);
                                            case "equals" -> proxy == args[0];
                                            case "toString" -> "StubDataSource";
                                            default -> null;
                                        };
                                    });
            var manager = new DataSourceTransactionManager(dataSource);
            manager.setNestedTransactionAllowed(false);
            this.tx = new SpringAttemptTransactions(dataSource, manager, 5);
        }
    }

    private static AttemptTransactions.Guard validGuard() {
        return new AttemptTransactions.Guard() {
            @Override
            public void requireValid() {}

            @Override
            public void invalidate(Throwable cause) {}
        };
    }

    @Test
    void afterCommitRunsStrictlyAfterPhysicalCommitAndSwallowsPublisherFailure() {
        var env = new StubJdbcEnv();
        var afterCommitCalls = new AtomicInteger();

        String result =
                env.tx.write(
                        () -> {
                            env.events.add("BUSINESS_WRITE");
                            SpringAttemptTransactions.afterCommit(
                                    env.dataSource,
                                    () -> {
                                        afterCommitCalls.incrementAndGet();
                                        env.events.add("AFTER_COMMIT_HOOK");
                                        throw new RuntimeException(
                                                "simulated crash/error between COMMIT and NATS publish");
                                    });
                            return "COMMITTED_OK";
                        },
                        validGuard());

        assertEquals("COMMITTED_OK", result, "afterCommit failure must never alter commit verdict");
        assertEquals(1, afterCommitCalls.get());
        assertEquals(
                List.of("BUSINESS_WRITE", "PHYSICAL_COMMIT", "AFTER_COMMIT_HOOK"),
                env.events,
                "afterCommit hook must run strictly after physical COMMIT");
    }

    @Test
    void afterCommitNeverRunsWhenTransactionRollsBack() {
        var env = new StubJdbcEnv();
        var afterCommitCalls = new AtomicInteger();

        AttemptTransactions.Failure failure =
                assertThrows(
                        AttemptTransactions.Failure.class,
                        () ->
                                env.tx.write(
                                        () -> {
                                            env.events.add("BUSINESS_WRITE");
                                            SpringAttemptTransactions.afterCommit(
                                                    env.dataSource,
                                                    () -> {
                                                        afterCommitCalls.incrementAndGet();
                                                        env.events.add("AFTER_COMMIT_HOOK");
                                                    });
                                            throw new IllegalStateException("business abort");
                                        },
                                        validGuard()));

        assertEquals(AttemptTransactions.Resolution.ROLLED_BACK, failure.resolution());
        assertEquals(0, afterCommitCalls.get(), "afterCommit must never run on rollback");
        assertEquals(List.of("BUSINESS_WRITE", "PHYSICAL_ROLLBACK"), env.events);
    }
}
