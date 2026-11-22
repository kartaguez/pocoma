package com.kartaguez.pocoma.runtime.task;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.projection.CommandTerminalResult;
import com.kartaguez.pocoma.infra.postgres.read.JdbcCommandTerminalResultStore;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.AttemptTransactions.Failure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;
import org.testcontainers.postgresql.PostgreSQLContainer;

final class C3SqlShape {
    private static final String SUFFIX = """
            with allocation as (
                update read_availability_counter set position=position+1 where family=? returning family, position
            )
            insert into read_availability_event(family,position,event_id,projection_key)
            select family,position,?,? from allocation returning position
            """;

    static void check(PostgreSQLContainer pg, Path output) throws Exception {
        try (var f = new ReadParticipantsFixture(pg, "c3-grouped-sql")) {
            C3Complement.enablePilot(f);
            var db = f.f.read;
            var first = UUID.randomUUID();
            var second = UUID.randomUUID();
            var event = UUID.randomUUID();
            assertEquals(1L, finalizeWithCte(db, first, event));
            var failure = assertThrows(Failure.class, () -> finalizeWithCte(db, second, event));
            Throwable cause = failure;
            while (cause != null && !(cause instanceof SQLException)) cause = cause.getCause();
            assertInstanceOf(SQLException.class, cause);
            assertEquals("23505", ((SQLException) cause).getSQLState());
            assertEquals(1L, db.oracle.queryForObject("select position from read_availability_counter", Long.class));
            assertEquals(0L, db.oracle.queryForObject(
                    "select count(*) from read_command_result where request_id=?", Long.class, second));
            assertEquals(2L, finalizeWithCte(db, second, UUID.randomUUID()));
            Files.writeString(output.resolve("grouped-sql.txt"), SUFFIX
                    + "\nPASS actual pilot V5 triggers: first position=1, duplicate UUID SQLSTATE 23505"
                    + " rolled back effect and counter, next committed position=2.\n"
                    + "Direct store fixture, no slot or concurrent qualification of this SQL variant.\n");
        }
    }

    private static long finalizeWithCte(SubobjectPostgresFixture db, UUID key, UUID event) {
        var store = JdbcCommandTerminalResultStore.availabilityPilot(db.source, db.tx);
        var value = new CommandTerminalResult(key, "CreatePot", new ExternalIdentity("i2", "actor"),
                key, "CHANGED", 1L, null);
        return db.tx.write(() -> {
            assertTrue(store.insertOrVerify(value));
            store.requireCompletionReady(value, true);
            return SpringAttemptTransactions.guarded(db.source, () -> {
                SpringAttemptTransactions.closePublication(db.source);
                return db.sql.queryForObject(SUFFIX, Long.class, "COMMAND_RESULT_AVAILABLE", event, key);
            });
        });
    }
}
