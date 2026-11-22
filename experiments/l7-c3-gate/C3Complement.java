package com.kartaguez.pocoma.runtime.task;

import static com.kartaguez.pocoma.compose.polling.materialize.ReadParticipants.Name.COMMAND_RESULT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.kartaguez.pocoma.engine.materialize.results.MaterializeCommandResult;
import com.kartaguez.pocoma.infra.postgres.read.JdbcCommandTerminalResultStore;
import com.kartaguez.pocoma.infra.postgres.source.JdbcCommandTerminalSource;
import java.nio.file.Path;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

public final class C3Complement {
    public static void main(String[] args) throws Exception {
        var output = Path.of(args[0]);
        var tooling = Path.of(args[1]);
        try (var pg = ReadParticipantsContainer.create().withCommand(
                "postgres", "-c", "shared_preload_libraries=pg_stat_statements",
                "-c", "track_io_timing=on", "-c", "pg_stat_statements.track_utility=on",
                "-c", "log_lock_waits=on", "-c", "deadlock_timeout=5ms")) {
            pg.start();
            var admin = admin(pg);
            System.out.println("SERVER " + admin.queryForMap("""
                    select version(), current_setting('synchronous_commit') as synchronous_commit,
                           current_setting('fsync') as fsync, current_setting('full_page_writes') as full_page_writes,
                           current_setting('deadlock_timeout') as deadlock_timeout,
                           current_setting('track_io_timing') as track_io_timing
                    """) + " image=" + pg.getDockerImageName());
            SubobjectPostgresFixture.initialize(pg);
            if (args[2].equals("order")) {
                new C3Order(pg, output).run();
                C3SqlShape.check(pg, output);
            } else {
                admin.execute("create extension pg_stat_statements");
                boolean inspectWal = args[2].equals("wal");
                if (inspectWal) admin.execute("create extension pg_walinspect");
                new C3Costs(pg, output, tooling, Integer.parseInt(args[3]), inspectWal).run();
            }
        }
    }

    static JdbcTemplate admin(PostgreSQLContainer pg) {
        return new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
    }

    static void enablePilot(ReadParticipantsFixture f) {
        var db = f.f.read;
        assertEquals(1, Flyway.configure().dataSource(db.admin).schemas(db.schema)
                .defaultSchema(db.schema).createSchemas(false)
                .locations("classpath:db/read/migration", "classpath:db/read/pilot/migration")
                .placeholders(Map.of("expectedDatabase", db.pg.getDatabaseName(), "expectedSchema", db.schema,
                        "expectedAdmin", db.adminRole, "expectedRuntime", db.runtimeRole))
                .load().migrate().migrationsExecuted);
        enablePilotEngineOnly(f);
    }

    static void enablePilotEngineOnly(ReadParticipantsFixture f) {
        var db = f.f.read;
        f.engines.put(COMMAND_RESULT, f.engine(COMMAND_RESULT,
                new MaterializeCommandResult(new JdbcCommandTerminalSource(f.f.primary, f.f.sourceOwner),
                        JdbcCommandTerminalResultStore.availabilityPilot(db.source, db.tx)),
                db.source, db.tx));
    }
}
