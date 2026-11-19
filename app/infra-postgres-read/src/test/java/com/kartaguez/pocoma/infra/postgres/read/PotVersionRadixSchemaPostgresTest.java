package com.kartaguez.pocoma.infra.postgres.read;

import com.kartaguez.pocoma.domain.projection.PotVersion;
import com.kartaguez.pocoma.domain.projection.radix.*;
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
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PotVersionRadixSchemaPostgresTest {

    @Container
    static final PostgreSQLContainer PG = ReadStoreFixture.container();

    private record CandidateFixture(
            String schema, String adminRole, String runtimeRole,
            DataSource admin, DataSource runtime, JdbcTemplate oracle, JdbcTemplate sql) {

        static CandidateFixture create(boolean migrateV3) {
            String schema = "c2b1_" + UUID.randomUUID().toString().replace("-", "");
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
            fx.migrate(migrateV3 ? "3" : "2");
            return fx;
        }

        private static DataSource ds(String schema, String role) {
            return new DriverManagerDataSource(PG.getJdbcUrl() + "&currentSchema=" + schema + "&socketTimeout=20", role, "fixture");
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
    void candidateMigrationRefusesNonEmptyReadPotVersion() {
        CandidateFixture fx = CandidateFixture.create(false);
        UUID pot = UUID.randomUUID();
        fx.sql.update("insert into read_pot_version(pot_id,pot_version,schema_version,payload) values (?,?,1,'{}'::jsonb)", pot, 1L);
        assertThrows(FlywayException.class, () -> fx.migrate("3"));
    }

    @Test
    void immutabilityStructuralGuardsAndPrivilegesAreEnforced() {
        CandidateFixture fx = CandidateFixture.create(true);

        // Build a real Radix-16 tree with 80 expenses (> 64 -> internal root + leaves) and 10 shareholders (leaf)
        Random rnd = new Random(12345L);
        List<PotVersion.Shareholder> shareholders = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            shareholders.add(new PotVersion.Shareholder(new UUID(rnd.nextLong(), rnd.nextLong()), 1, 1, 1, false, i % 2 == 0));
        }
        List<PotVersion.Expense> expenses = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            expenses.add(new PotVersion.Expense(new UUID(rnd.nextLong(), rnd.nextLong()), 1, 1, 1, false));
        }
        Radix16Tree.BuildResult shTree = Radix16Tree.buildFullShareholders(shareholders);
        Radix16Tree.BuildResult exTree = Radix16Tree.buildFullExpenses(expenses);

        PotUserSet authSet = PotUserSet.of(List.of(UUID.randomUUID(), UUID.randomUUID())).orElseThrow();
        insertUserSet(fx.sql, authSet);

        List<Radix16Node> allNewNodes = new ArrayList<>();
        allNewNodes.addAll(shTree.newNodes().values());
        allNewNodes.addAll(exTree.newNodes().values());
        for (List<Radix16Node> level : Radix16Tree.groupBottomUpByDepth(allNewNodes)) {
            insertNodeBatch(fx.sql, level);
        }

        UUID potId = UUID.randomUUID();
        fx.sql.update("""
                insert into read_pot_version(
                  pot_id, pot_version, schema_version, header_version, details_version, deleted,
                  shareholders_count, expenses_count, shareholders_root, expenses_root,
                  authorized_users_hash, attached_active_users_hash)
                values (?,1,1,1,1,false,?,?,?,?,?,?)""",
                potId, 10, 80, shTree.rootHash().bytes(), exTree.rootHash().bytes(),
                authSet.hash().bytes(), authSet.hash().bytes());
        fx.sql.update("insert into read_current_pot(pot_id, pot_version) values (?,1)", potId);

        // 1. Immutability (even as admin!)
        assertSqlState("23514", () -> fx.oracle.update("update read_pot_user_set set user_count = 99"));
        assertSqlState("23514", () -> fx.oracle.update("delete from read_pot_user_set"));
        assertSqlState("23514", () -> fx.oracle.execute("truncate read_pot_user_set cascade"));

        assertSqlState("23514", () -> fx.oracle.update("update read_pot_version_node set entry_count = 99"));
        assertSqlState("23514", () -> fx.oracle.update("delete from read_pot_version_node"));
        assertSqlState("23514", () -> fx.oracle.execute("truncate read_pot_version_node cascade"));

        assertSqlState("23514", () -> fx.oracle.update("update read_pot_version set deleted = true"));
        assertSqlState("23514", () -> fx.oracle.update("delete from read_pot_version"));
        assertSqlState("23514", () -> fx.oracle.execute("truncate read_pot_version cascade"));

        // 2. Runtime privileges (permission denied 42501)
        assertSqlState("42501", () -> fx.sql.update("update read_pot_version_node set entry_count = 99"));
        assertSqlState("42501", () -> fx.sql.update("delete from read_pot_version_node"));
        assertSqlState("42501", () -> fx.sql.update("update read_current_pot set pot_id = ?", UUID.randomUUID()));

        // 3. Structural guard: missing child -> 23503
        byte[] missingChildHash = new byte[32];
        missingChildHash[0] = 0x77;
        Radix16Node anyExLeaf = exTree.newNodes().values().stream().filter(Radix16Node::isLeaf).findFirst().orElseThrow();
        byte[] badInternalHash = new byte[32];
        badInternalHash[0] = 0x55;
        assertSqlState("23503", () -> insertRawInternal(fx.sql, badInternalHash, "E", 65,
                new byte[][]{anyExLeaf.hash().bytes(), missingChildHash}, new byte[10]));

        // 4. Structural guard: cross-tree child (E internal pointing to S leaf) -> 23503
        Radix16Node shLeaf = shTree.newNodes().get(shTree.rootHash());
        byte[] crossTreeHash = new byte[32];
        crossTreeHash[0] = 0x56;
        assertSqlState("23503", () -> insertRawInternal(fx.sql, crossTreeHash, "E", anyExLeaf.entryCount() + shLeaf.entryCount(),
                new byte[][]{anyExLeaf.hash().bytes(), shLeaf.hash().bytes()}, new byte[10]));

        // 5. Structural guard: self-loop -> 23514
        byte[] selfLoopHash = new byte[32];
        selfLoopHash[0] = 0x57;
        assertSqlState("23514", () -> insertRawInternal(fx.sql, selfLoopHash, "E", 65,
                new byte[][]{selfLoopHash, anyExLeaf.hash().bytes()}, new byte[10]));

        // 6. Structural guard: duplicate child hash -> 23514
        byte[] dupChildHash = new byte[32];
        dupChildHash[0] = 0x58;
        assertSqlState("23514", () -> insertRawInternal(fx.sql, dupChildHash, "E", 65,
                new byte[][]{anyExLeaf.hash().bytes(), anyExLeaf.hash().bytes()}, new byte[10]));

        // 7. Structural guard: wrong entry_count sum -> 23514
        List<Radix16Node> exLeaves = exTree.newNodes().values().stream().filter(Radix16Node::isLeaf).toList();
        assertTrue(exLeaves.size() >= 2);
        byte[] badSumHash = new byte[32];
        badSumHash[0] = 0x59;
        assertSqlState("23514", () -> insertRawInternal(fx.sql, badSumHash, "E", 999,
                new byte[][]{exLeaves.get(0).hash().bytes(), exLeaves.get(1).hash().bytes()}, new byte[10]));

        // 8. Root guard: cross-tree root on read_pot_version -> 23514
        assertSqlState("23514", () -> fx.sql.update("""
                insert into read_pot_version(
                  pot_id, pot_version, schema_version, header_version, details_version, deleted,
                  shareholders_count, expenses_count, shareholders_root, expenses_root,
                  authorized_users_hash, attached_active_users_hash)
                values (?,2,1,1,1,false,?,0,?,null,null,null)""",
                potId, 80, exTree.rootHash().bytes()));

        // 9. Root guard: mismatched entry_count on read_pot_version -> 23514
        assertSqlState("23514", () -> fx.sql.update("""
                insert into read_pot_version(
                  pot_id, pot_version, schema_version, header_version, details_version, deleted,
                  shareholders_count, expenses_count, shareholders_root, expenses_root,
                  authorized_users_hash, attached_active_users_hash)
                values (?,3,1,1,1,false,?,0,?,null,null,null)""",
                potId, 99, shTree.rootHash().bytes()));
    }

    @Test
    void concurrentOverlappingBottomUpInsertsCompleteWithoutDeadlock() throws Exception {
        CandidateFixture fx = CandidateFixture.create(true);
        Random rnd = new Random(98765L);
        List<PotVersion.Expense> sharedBase = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            sharedBase.add(new PotVersion.Expense(new UUID(rnd.nextLong(), rnd.nextLong()), 1, 1, 1, false));
        }

        int threads = 4;
        List<List<List<Radix16Node>>> perThreadLevels = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            List<PotVersion.Expense> variant = new ArrayList<>(sharedBase);
            variant.add(new PotVersion.Expense(new UUID(rnd.nextLong(), rnd.nextLong()), 1, 1, 1, false));
            Radix16Tree.BuildResult built = Radix16Tree.buildFullExpenses(variant);
            perThreadLevels.add(Radix16Tree.groupBottomUpByDepth(built.newNodes().values()));
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            List<List<Radix16Node>> levels = perThreadLevels.get(t);
            futures.add(pool.submit(() -> {
                JdbcTemplate jdbc = new JdbcTemplate(fx.runtime);
                barrier.await(10, TimeUnit.SECONDS);
                for (List<Radix16Node> level : levels) {
                    insertNodeBatch(jdbc, level);
                }
                return null;
            }));
        }
        pool.shutdown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        Integer count = fx.sql.queryForObject("select count(*) from read_pot_version_node", Integer.class);
        assertNotNull(count);
        assertTrue(count > 0);
    }

    private static void insertUserSet(JdbcTemplate jdbc, PotUserSet set) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into read_pot_user_set(set_hash,schema_version,user_count,user_ids) values (?,?,?,?) on conflict (set_hash) do nothing");
            ps.setBytes(1, set.hash().bytes());
            ps.setInt(2, set.schemaVersion());
            ps.setInt(3, set.userCount());
            Array arr = con.createArrayOf("uuid", set.userIds().toArray(UUID[]::new));
            ps.setArray(4, arr);
            return ps;
        });
    }

    private static void insertNodeBatch(JdbcTemplate jdbc, List<Radix16Node> level) {
        for (Radix16Node n : level) {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                        insert into read_pot_version_node(
                          node_hash, schema_version, node_kind, tree_kind, entry_count, prefix, child_hashes, payload)
                        values (?,?,?,?,?,?,?,?) on conflict (node_hash) do nothing""");
                ps.setBytes(1, n.hash().bytes());
                ps.setInt(2, n.schemaVersion());
                ps.setString(3, String.valueOf(n.nodeKind()));
                ps.setString(4, String.valueOf(n.treeKind()));
                ps.setInt(5, n.entryCount());
                ps.setString(6, n.prefix());
                if (n.childHashes() == null) {
                    ps.setNull(7, java.sql.Types.ARRAY);
                } else {
                    byte[][] hashes = n.childHashes().stream().map(ContentHash::bytes).toArray(byte[][]::new);
                    ps.setArray(7, con.createArrayOf("bytea", hashes));
                }
                ps.setBytes(8, n.payload());
                return ps;
            });
        }
    }

    private static void insertRawInternal(
            JdbcTemplate jdbc, byte[] hash, String treeKind, int entryCount, byte[][] children, byte[] payload) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into read_pot_version_node(
                      node_hash, schema_version, node_kind, tree_kind, entry_count, prefix, child_hashes, payload)
                    values (?,1,'I',?,?,'',?,?)""");
            ps.setBytes(1, hash);
            ps.setString(2, treeKind);
            ps.setInt(3, entryCount);
            ps.setArray(4, con.createArrayOf("bytea", children));
            ps.setBytes(5, payload);
            return ps;
        });
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
