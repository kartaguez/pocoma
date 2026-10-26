package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand.UpdatePotDetails;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

import javax.sql.DataSource;

/**
 * Same real engine/SQL harness on both Git revisions. Fixture/admission are outside measurements.
 */
@Testcontainers
class WriteRevisionPerformancePostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = CommandExecutionPostgresTest.POSTGRES;

    @Test
    void f201WriteDepthProfiles() throws Exception {
        var profiles = new ArrayList<Object>();
        for (int depth : new int[] {100, 1000, 10000}) {
            var f = new CommandExecutionPostgresTest();
            try {
                long fixtureStart = System.nanoTime();
                f.setup();
                f.base();
                new TransactionTemplate(new DataSourceTransactionManager(f.source))
                        .execute(
                                s -> {
                                    f.jdbc.update(
                                            "update v3_pot_details set active_to=3 where pot_id=?"
                                                + " and active_to is null",
                                            f.p.value());
                                    f.jdbc.update(
                                            "insert into v3_pot_version(pot_id,version,recorded_at)"
                                                + " select ?,v,clock_timestamp() from"
                                                + " generate_series(3,?) v",
                                            f.p.value(),
                                            depth);
                                    f.jdbc.update(
                                            "insert into"
                                                + " v3_pot_details(pot_id,object_id,local_version,active_from,active_to,label,creator_id)"
                                                + " select ?,?,v-1,v,case when v=? then null else"
                                                + " v+1 end,'L'||v,? from generate_series(3,?) v",
                                            f.p.value(),
                                            f.p.value(),
                                            depth,
                                            f.creator.value(),
                                            depth);
                                    return null;
                                });
                f.jdbc.execute("analyze");
                long fixtureNanos = System.nanoTime() - fixtureStart;
                var meter = new Meter(f.source);
                var manager = new DataSourceTransactionManager(meter);
                manager.setNestedTransactionAllowed(false);
                var owner = new SpringAttemptTransactions(meter, manager);
                var store =
                        new L1CommandStore.Fixture(meter, new JdbcPotCommandStore(meter, owner));
                var reader = new JdbcPotHistoryAdapter(meter);
                var writer = new JdbcPotRevisionAdapter(meter);
                var timing = new Timing(owner);
                var engine =
                        new L1CommandCompatibilityHarness(
                                timing,
                                store,
                                f.new FixtureAuthority(meter),
                                reader,
                                writer,
                                new L1CommandCompatibilityHarness.RetryPolicy(3, Duration.ZERO),
                                Clock.systemUTC());
                var samples = new ArrayList<Object>();
                for (int repeat = -5; repeat < 10; repeat++) {
                    long version = depth + repeat + 5L;
                    var command =
                            f.command(
                                    version, new UpdatePotDetails(new Label("measured-" + repeat)));
                    var token = f.admit(command);
                    meter.reset();
                    meter.enabled = true;
                    long start = System.nanoTime();
                    var result = engine.execute(command.requestId(), token);
                    long total = System.nanoTime() - start;
                    meter.enabled = false;
                    assertEquals(
                            L1CommandCompatibilityHarness.Status.COMPLETED,
                            result.status(),
                            result.toString());
                    assertEquals(version + 1, result.result().orElseThrow().resultingPotVersion());
                    if (repeat >= 0)
                        samples.add(
                                Map.of(
                                        "repeat",
                                        repeat,
                                        "initial_version",
                                        version,
                                        "preparation_ns",
                                        timing.preparation,
                                        "finalization_ns",
                                        timing.finalization,
                                        "total_ns",
                                        total,
                                        "sql_count",
                                        meter.statements,
                                        "java_rows",
                                        meter.rows));
                }
                var emitted = List.copyOf(meter.queries);
                var plans = new ArrayList<Object>();
                var unique = new LinkedHashMap<String, Query>();
                emitted.stream()
                        .filter(q -> q.sql.startsWith("select") && q.sql.contains("from v3_"))
                        .forEach(q -> unique.put(q.sql, q));
                new TransactionTemplate(manager)
                        .execute(
                                s -> {
                                    var jdbc =
                                            new org.springframework.jdbc.core.JdbcTemplate(meter);
                                    for (var q : unique.values()) {
                                        String plan =
                                                jdbc.queryForObject(
                                                        "explain (analyze,buffers,format json) "
                                                                + q.sql,
                                                        String.class,
                                                        q.parameters);
                                        try {
                                            plans.add(
                                                    Map.of(
                                                            "sql",
                                                            q.sql,
                                                            "plan",
                                                            new ObjectMapper().readTree(plan)));
                                        } catch (Exception ex) {
                                            throw new IllegalStateException(ex);
                                        }
                                    }
                                    // Measure the actual close DML on the current row, then roll
                                    // back the diagnostic.
                                    String close =
                                            "update v3_pot_details set active_to=? where pot_id=?"
                                                + " and object_id=? and local_version=? and"
                                                + " active_to is null and active_from<?";
                                    String plan =
                                            jdbc.queryForObject(
                                                    "explain (analyze,buffers,format json) "
                                                            + close,
                                                    String.class,
                                                    depth + 16L,
                                                    f.p.value(),
                                                    f.p.value(),
                                                    depth + 14L,
                                                    depth + 16L);
                                    try {
                                        plans.add(
                                                Map.of(
                                                        "sql",
                                                        close,
                                                        "plan",
                                                        new ObjectMapper().readTree(plan)));
                                    } catch (Exception ex) {
                                        throw new IllegalStateException(ex);
                                    }
                                    s.setRollbackOnly();
                                    return null;
                                });
                owner.read(
                        context -> {
                            reader.verifyHistory(f.p);
                            return null;
                        });
                profiles.add(
                        Map.of(
                                "depth",
                                depth,
                                "fixture_ns",
                                fixtureNanos,
                                "samples",
                                samples,
                                "sql",
                                emitted.stream().map(Query::sql).toList(),
                                "plans",
                                plans));
            } finally {
                f.threads.shutdownNow();
            }
        }
        var output = Path.of("target/v3-pot-evidence/F2-write-performance.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValue(
                        output.toFile(),
                        Map.of(
                                "variant",
                                System.getProperty("f2.variant", "current"),
                                "warmups",
                                5,
                                "repetitions",
                                10,
                                "postgres",
                                POSTGRES.getDockerImageName(),
                                "java",
                                System.getProperty("java.runtime.version"),
                                "profiles",
                                profiles));
    }

    static final class Timing extends ForwardingTransactions {
        final AttemptTransactions delegate;
        long preparation, finalization;

        Timing(AttemptTransactions delegate) {
            super(delegate);
            this.delegate = delegate;
        }

        public <T> T read(java.util.function.Function<RecoveryReadContext, T> action) {
            long start = System.nanoTime();
            try {
                return delegate.read(action);
            } finally {
                preparation = System.nanoTime() - start;
            }
        }

        public <T> T write(Supplier<T> action) {
            long start = System.nanoTime();
            try {
                return delegate.write(action);
            } finally {
                finalization = System.nanoTime() - start;
            }
        }
    }

    record Query(String sql, Object[] parameters) {}

    /**
     * Test-only, single-thread instrumentation; counts executions and successful ResultSet.next().
     */
    static final class Meter extends DelegatingDataSource {
        boolean enabled;
        long statements, rows;
        final List<Query> queries = new ArrayList<>();

        Meter(DataSource source) {
            super(source);
        }

        void reset() {
            statements = 0;
            rows = 0;
            queries.clear();
        }

        @Override
        public Connection getConnection() throws SQLException {
            return connection(super.getConnection());
        }

        @Override
        public Connection getConnection(String u, String p) throws SQLException {
            return connection(super.getConnection(u, p));
        }

        Connection connection(Connection target) {
            return proxy(
                    Connection.class,
                    target,
                    (method, args) -> {
                        Object value = invoke(target, method, args);
                        if (value instanceof PreparedStatement statement
                                && method.getName().equals("prepareStatement"))
                            return statement(statement, (String) args[0]);
                        if (value instanceof Statement statement
                                && method.getName().equals("createStatement"))
                            return plain(statement);
                        return value;
                    });
        }

        Statement plain(Statement target) {
            return proxy(
                    Statement.class,
                    target,
                    (method, args) -> {
                        if (enabled && method.getName().startsWith("execute")) statements++;
                        Object value = invoke(target, method, args);
                        return value instanceof ResultSet rs ? result(rs) : value;
                    });
        }

        PreparedStatement statement(PreparedStatement target, String sql) {
            var parameters = new TreeMap<Integer, Object>();
            return proxy(
                    PreparedStatement.class,
                    target,
                    (method, args) -> {
                        if (method.getName().startsWith("set")
                                && args != null
                                && args.length >= 2
                                && args[0] instanceof Integer position)
                            parameters.put(
                                    position, method.getName().equals("setNull") ? null : args[1]);
                        if (enabled && method.getName().startsWith("execute")) {
                            statements++;
                            queries.add(new Query(sql, parameters.values().toArray()));
                        }
                        Object value = invoke(target, method, args);
                        return value instanceof ResultSet rs ? result(rs) : value;
                    });
        }

        ResultSet result(ResultSet target) {
            return proxy(
                    ResultSet.class,
                    target,
                    (method, args) -> {
                        Object value = invoke(target, method, args);
                        if (enabled
                                && method.getName().equals("next")
                                && Boolean.TRUE.equals(value)) rows++;
                        return value;
                    });
        }

        interface Call {
            Object call(Method method, Object[] args) throws Throwable;
        }

        static <T> T proxy(Class<T> type, T target, Call call) {
            return type.cast(
                    Proxy.newProxyInstance(
                            type.getClassLoader(),
                            new Class<?>[] {type},
                            (p, m, a) -> call.call(m, a)));
        }

        static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        }
    }
}
