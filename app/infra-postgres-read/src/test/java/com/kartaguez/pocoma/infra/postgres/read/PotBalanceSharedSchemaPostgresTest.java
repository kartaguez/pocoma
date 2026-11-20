package com.kartaguez.pocoma.infra.postgres.read;

import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.projection.PotBalance;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.ShareholderEntry;
import com.kartaguez.pocoma.domain.projection.balance.PotBalanceAggregateBinaryCodec;
import com.kartaguez.pocoma.domain.projection.balance.PotBalanceBinaryCodec;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PotBalanceSharedSchemaPostgresTest {

    @Container
    static final PostgreSQLContainer PG = ReadStoreFixture.container();

    private record CandidateFixture(
            String schema, String adminRole, String runtimeRole,
            DataSource admin, DataSource runtime, JdbcTemplate oracle, JdbcTemplate sql) {

        static CandidateFixture create(boolean migrateV4) {
            String schema = "c2b2_" + UUID.randomUUID().toString().replace("-", "");
            String adminRole = schema + "_a";
            String runtimeRole = schema + "_r";
            JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
            root.execute("create extension if not exists pgcrypto with schema public");
            root.execute("create role " + adminRole + " login password 'fixture'");
            root.execute("create role " + runtimeRole + " login password 'fixture'");
            root.execute("create schema " + schema + " authorization " + adminRole);
            root.execute("grant usage on schema " + schema + " to " + runtimeRole);
            DataSource admin = ds(schema, adminRole);
            DataSource runtime = ds(schema, runtimeRole);
            CandidateFixture fx = new CandidateFixture(
                    schema, adminRole, runtimeRole, admin, runtime,
                    new JdbcTemplate(admin), new JdbcTemplate(runtime));
            fx.migrate(migrateV4 ? "4" : "3");
            return fx;
        }

        private static DataSource ds(String schema, String role) {
            return new DriverManagerDataSource(
                    PG.getJdbcUrl() + "&currentSchema=" + schema + "&socketTimeout=20", role, "fixture");
        }

        int migrate(String target) {
            return Flyway.configure()
                    .dataSource(admin)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(false)
                    .locations("classpath:db/read/migration")
                    .target(target)
                    .placeholders(Map.of(
                            "expectedDatabase", PG.getDatabaseName(),
                            "expectedSchema", schema,
                            "expectedAdmin", adminRole,
                            "expectedRuntime", runtimeRole))
                    .load()
                    .migrate()
                    .migrationsExecuted;
        }
    }

    @Test
    void candidateMigrationRefusesNonEmptyReadPotBalance() {
        CandidateFixture fx = CandidateFixture.create(false);
        UUID pot = UUID.randomUUID();
        fx.sql.update("insert into read_pot_balance(pot_id,pot_version,schema_version,payload) values (?,1,1,'[]'::jsonb)", pot);
        assertThrows(FlywayException.class, () -> fx.migrate("4"));
    }

    @Test
    void candidateMigrationRefusesNonEmptyReadPotBalanceAggregate() {
        CandidateFixture fx = CandidateFixture.create(false);
        UUID pot = UUID.randomUUID();
        fx.sql.update("insert into read_pot_balance(pot_id,pot_version,schema_version,payload) values (?,1,1,'[]'::jsonb)", pot);
        fx.sql.update("insert into read_pot_balance_aggregate(pot_id,pot_version,schema_version,bucket_count,payload) values (?,1,1,0,'{}'::jsonb)", pot);
        assertThrows(FlywayException.class, () -> fx.migrate("4"));
    }

    @Test
    void schemaConstraintsFkImmutabilityTriggersIndexesAndPrivilegesAreEnforced() {
        CandidateFixture fx = CandidateFixture.create(true);

        UUID potId = UUID.randomUUID();
        UUID sh1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID sh2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

        var balEncoded = PotBalanceBinaryCodec.encode(
                new PotBalance(potId, 1L, Map.of(sh1, new Fraction(10, 1), sh2, new Fraction(-10, 1))));
        var aggEncoded = PotBalanceAggregateBinaryCodec.encodeWithShareholderSet(
                new PotBalanceAggregate(
                        potId, 1L,
                        List.of(
                                new ShareholderEntry(sh1, Fraction.ONE, false, Fraction.ZERO),
                                new ShareholderEntry(sh2, Fraction.ONE, false, Fraction.ZERO)),
                        Map.of()),
                balEncoded.shareholderSet());
        var shSet = balEncoded.shareholderSet();
        var balContent = balEncoded.content();
        var aggContent = aggEncoded.content();

        // Valid inserts via runtime role
        fx.sql.update("""
                insert into read_pot_shareholder_set(set_hash, schema_version, shareholders_count, payload)
                values (?, ?, ?, ?)""",
                shSet.hash().bytes(), shSet.schemaVersion(), shSet.shareholdersCount(), shSet.payload());
        fx.sql.update("""
                insert into read_pot_balance_content(content_hash, schema_version, shareholders_count, shareholder_set_hash, payload)
                values (?, ?, ?, ?, ?)""",
                balContent.hash().bytes(), balContent.schemaVersion(), balContent.shareholdersCount(),
                balContent.shareholderSetHash().bytes(), balContent.payload());
        fx.sql.update("""
                insert into read_pot_balance_aggregate_content(content_hash, schema_version, shareholders_count, bucket_count, shareholder_set_hash, payload)
                values (?, ?, ?, ?, ?, ?)""",
                aggContent.hash().bytes(), aggContent.schemaVersion(), aggContent.shareholdersCount(),
                aggContent.bucketCount(), aggContent.shareholderSetHash().bytes(), aggContent.payload());
        fx.sql.update("insert into read_pot_balance(pot_id, pot_version, content_hash) values (?, 1, ?)",
                potId, balContent.hash().bytes());
        fx.sql.update("insert into read_pot_balance_aggregate(pot_id, pot_version, content_hash) values (?, 1, ?)",
                potId, aggContent.hash().bytes());

        // 1. Immutability enforced even as admin (23514) on all 5 tables
        for (String table : List.of(
                "read_pot_shareholder_set",
                "read_pot_balance_content",
                "read_pot_balance_aggregate_content",
                "read_pot_balance",
                "read_pot_balance_aggregate")) {
            assertSqlState("23514", () -> fx.oracle.update("delete from " + table));
            assertSqlState("23514", () -> fx.oracle.execute("truncate " + table + " cascade"));
        }
        assertSqlState("23514", () -> fx.oracle.update("update read_pot_shareholder_set set shareholders_count = 99"));
        assertSqlState("23514", () -> fx.oracle.update("update read_pot_balance_content set shareholders_count = 99"));
        assertSqlState("23514", () -> fx.oracle.update("update read_pot_balance_aggregate_content set bucket_count = 99"));
        assertSqlState("23514", () -> fx.oracle.update("update read_pot_balance set pot_version = 99"));
        assertSqlState("23514", () -> fx.oracle.update("update read_pot_balance_aggregate set pot_version = 99"));

        // Verify all 10 immutability triggers are ENABLE ALWAYS (tgenabled = 'A')
        Integer alwaysTriggerCount = fx.oracle.queryForObject("""
                select count(*)
                from pg_trigger t
                join pg_class c on c.oid = t.tgrelid
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = ?
                  and c.relname in ('read_pot_shareholder_set','read_pot_balance_content','read_pot_balance_aggregate_content','read_pot_balance','read_pot_balance_aggregate')
                  and not t.tgisinternal
                  and t.tgenabled = 'A'""",
                Integer.class, fx.schema);
        assertEquals(10, alwaysTriggerCount);

        // 2. Runtime privileges (permission denied 42501)
        assertSqlState("42501", () -> fx.sql.update("update read_pot_shareholder_set set shareholders_count = 99"));
        assertSqlState("42501", () -> fx.sql.update("delete from read_pot_shareholder_set"));
        assertSqlState("42501", () -> fx.sql.update("update read_pot_balance_content set shareholders_count = 99"));
        assertSqlState("42501", () -> fx.sql.update("delete from read_pot_balance_content"));
        assertSqlState("42501", () -> fx.sql.update("update read_pot_balance_aggregate_content set bucket_count = 99"));
        assertSqlState("42501", () -> fx.sql.update("delete from read_pot_balance_aggregate_content"));
        assertSqlState("42501", () -> fx.sql.update("update read_pot_balance set pot_version = 2"));
        assertSqlState("42501", () -> fx.sql.update("delete from read_pot_balance"));
        assertSqlState("42501", () -> fx.sql.update("update read_pot_balance_aggregate set pot_version = 2"));
        assertSqlState("42501", () -> fx.sql.update("delete from read_pot_balance_aggregate"));

        // 3. FK integrity (23503)
        byte[] missingHash = new byte[32];
        missingHash[0] = 0x42;
        assertSqlState("23503", () -> fx.sql.update(
                "insert into read_pot_balance_content(content_hash, schema_version, shareholders_count, shareholder_set_hash, payload) values (?, 1, 0, ?, ?)",
                missingHash, missingHash, new byte[4]));
        assertSqlState("23503", () -> fx.sql.update(
                "insert into read_pot_balance_aggregate_content(content_hash, schema_version, shareholders_count, bucket_count, shareholder_set_hash, payload) values (?, 1, 0, 0, ?, ?)",
                missingHash, missingHash, new byte[8]));
        assertSqlState("23503", () -> fx.sql.update(
                "insert into read_pot_balance(pot_id, pot_version, content_hash) values (?, 2, ?)",
                potId, missingHash));
        assertSqlState("23503", () -> fx.sql.update(
                "insert into read_pot_balance_aggregate(pot_id, pot_version, content_hash) values (?, 2, ?)",
                potId, aggContent.hash().bytes()));
        fx.sql.update("insert into read_pot_balance(pot_id, pot_version, content_hash) values (?, 2, ?)",
                potId, balContent.hash().bytes());
        assertSqlState("23503", () -> fx.sql.update(
                "insert into read_pot_balance_aggregate(pot_id, pot_version, content_hash) values (?, 2, ?)",
                potId, missingHash));

        // 4. Check constraints (23514)
        assertSqlState("23514", () -> fx.sql.update(
                "insert into read_pot_shareholder_set(set_hash, schema_version, shareholders_count, payload) values (?, 1, 0, ?)",
                new byte[31], new byte[4]));
        assertSqlState("23514", () -> fx.sql.update(
                "insert into read_pot_shareholder_set(set_hash, schema_version, shareholders_count, payload) values (?, 1, 1, ?)",
                missingHash, new byte[4]));
        assertSqlState("23514", () -> fx.sql.update(
                "insert into read_pot_balance_content(content_hash, schema_version, shareholders_count, shareholder_set_hash, payload) values (?, 1, 0, ?, ?)",
                new byte[31], shSet.hash().bytes(), new byte[4]));
        assertSqlState("23514", () -> fx.sql.update(
                "insert into read_pot_balance_content(content_hash, schema_version, shareholders_count, shareholder_set_hash, payload) values (?, 1, 0, ?, ?)",
                missingHash, shSet.hash().bytes(), new byte[3]));
        assertSqlState("23514", () -> fx.sql.update(
                "insert into read_pot_balance_aggregate_content(content_hash, schema_version, shareholders_count, bucket_count, shareholder_set_hash, payload) values (?, 1, 0, 0, ?, ?)",
                missingHash, shSet.hash().bytes(), new byte[7]));

        // 5. Exactly 5 PK indexes, 0 secondary indexes
        Integer indexCount = fx.oracle.queryForObject("""
                select count(*)
                from pg_indexes
                where schemaname = ?
                  and tablename in ('read_pot_shareholder_set','read_pot_balance_content','read_pot_balance_aggregate_content','read_pot_balance','read_pot_balance_aggregate')""",
                Integer.class, fx.schema);
        assertEquals(5, indexCount);
    }

    private static void assertSqlState(String expectedState, Runnable action) {
        DataAccessException ex = assertThrows(DataAccessException.class, action::run);
        Throwable cause = ex;
        String found = null;
        while (cause != null) {
            if (cause instanceof SQLException sqlEx && sqlEx.getSQLState() != null) {
                found = sqlEx.getSQLState();
                break;
            }
            cause = cause.getCause();
        }
        assertEquals(expectedState, found, "Unexpected SQLState for exception: " + ex.getMessage());
    }
}
