package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Label;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.*;
import java.util.concurrent.*;

/** Deliberately corrupt fixtures; production writes never repair a chain. */
@Testcontainers
class WriteRevisionPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = PotHistoryPostgresTest.POSTGRES;
    PotHistoryPostgresTest f;

    @BeforeEach
    void setup() {
        f = new PotHistoryPostgresTest();
        f.freshSchema();
    }

    TransitionResult.Changed<Versioned<PotDetails>> proposal() {
        return f.in(
                () -> {
                    var view = f.current();
                    return f.change(
                            PotTransitions.updateDetails(
                                    view.facts(),
                                    view.facts().workingVersion(),
                                    f.present(view.details()),
                                    new Label(UUID.randomUUID().toString())));
                });
    }

    void advance() {
        var proposal = proposal();
        f.write(() -> f.commit(proposal, x -> f.writer.revisePotDetails(f.p, x)));
    }

    Map<String, Object> database() {
        var result = new LinkedHashMap<>(f.intervals());
        for (var table :
                List.of("pot_version", "pot_identity", "shareholder_identity", "expense_identity"))
            result.put(table, f.jdbc.queryForList("select * from v3_" + table));
        return result;
    }

    @Test
    void f202RejectsLocalCorruptionAndRollsBackCandidate() throws Exception {
        f.expense();
        advance();
        advance(); // V5, Details locales 1/2/3 ; Header locale1.
        var before = database();
        var corruptions =
                List.<Runnable>of(
                        () ->
                                f.jdbc.update(
                                        "update v3_pot_details set active_to=6 where"
                                                + " local_version=3"),
                        () ->
                                f.jdbc.update(
                                        "update v3_pot_details set local_version=40 where"
                                                + " local_version=3"),
                        () -> f.jdbc.update("delete from v3_pot_details where local_version=2"),
                        () ->
                                f.jdbc.update(
                                        "update v3_pot_details set active_from=2 where"
                                                + " local_version=1"),
                        () ->
                                f.jdbc.update(
                                        "update v3_pot_details set active_to=6 where"
                                                + " local_version=2"),
                        () ->
                                f.jdbc.update(
                                        "update v3_pot_details set active_from=6 where"
                                                + " local_version=3"),
                        () ->
                                f.jdbc.update(
                                        "update v3_pot_details set active_to=6 where"
                                                + " local_version=1"),
                        () -> {
                            f.jdbc.execute("drop index v3_pot_details_open");
                            f.jdbc.update(
                                    "insert into"
                                        + " v3_pot_details(pot_id,object_id,local_version,active_from,label,creator_id)"
                                        + " values (?,?,4,5,'duplicate',?)",
                                    f.p.value(),
                                    f.p.value(),
                                    f.creator.value());
                        });
        for (var corruption : corruptions) {
            var next = proposal();
            assertThrows(
                    PotHistoryPostgresTest.ForcedRollback.class,
                    () ->
                            f.write(
                                    () -> {
                                        // Missing/bad closed rows need deliberate constraint bypass
                                        // in this isolated fixture.
                                        f.jdbc.execute(
                                                "set local session_replication_role=replica");
                                        corruption.run();
                                        f.jdbc.execute("set local session_replication_role=origin");
                                        assertThrows(
                                                InconsistentPotFactsException.class,
                                                () ->
                                                        f.commit(
                                                                next,
                                                                x ->
                                                                        f.writer.revisePotDetails(
                                                                                f.p, x)));
                                        throw new PotHistoryPostgresTest.ForcedRollback();
                                    }));
            assertEquals(before, database());
        }
        f.evidence(
                "F2-local-corruptions",
                "8 injected variants rejected; full candidate and fixture rollback independently"
                        + " compared");
    }

    @Test
    void f203UnrelatedOldGapDoesNotBlockWriteButAuditRejects() throws Exception {
        f.expense();
        advance();
        advance();
        advance(); // V6 Details locales1..4
        // Gap entirely before current predecessor: no overlap with the revision being changed.
        f.write(() -> f.jdbc.update("update v3_pot_details set active_to=3 where local_version=1"));
        advance();
        assertEquals(7L, f.in(() -> f.reader.atVersion(f.p, 7).orElseThrow()).version());
        assertThrows(
                InconsistentPotFactsException.class,
                () ->
                        f.in(
                                () -> {
                                    f.reader.verifyHistory(f.p);
                                    return null;
                                }));
        f.evidence(
                "F2-foreign-corruption",
                "WRITE V7 succeeds without repair; exhaustive audit rejects old gap [3,4)");
    }

    @Test
    void f204FinancialReferenceAndIdentityCorruptionBlockWrite() throws Exception {
        f.expense();
        var view = f.in(f::current);
        var state = view.expenses().get(f.e);
        var proposal =
                f.change(
                        ExpenseTransitions.updateFinancials(
                                view.facts(),
                                3,
                                f.present(state.header()),
                                f.present(state.financials()),
                                f.financials(f.e, 150, f.b),
                                f.references(view)));
        var before = database();
        for (var corruption :
                List.<Runnable>of(
                        () ->
                                f.jdbc.update(
                                        "delete from v3_shareholder_header where object_id=?",
                                        f.a.value()),
                        () ->
                                f.jdbc.update(
                                        "update v3_shareholder_identity set created_at=3 where"
                                                + " object_id=?",
                                        f.b.value()),
                        () ->
                                f.jdbc.update(
                                        "update v3_shareholder_header set deleted=true where"
                                                + " object_id=?",
                                        f.b.value()),
                        () ->
                                f.jdbc.update(
                                        "delete from v3_expense_identity where object_id=?",
                                        f.e.value()))) {
            assertThrows(
                    PotHistoryPostgresTest.ForcedRollback.class,
                    () ->
                            f.write(
                                    () -> {
                                        f.jdbc.execute(
                                                "set local session_replication_role=replica");
                                        corruption.run();
                                        f.jdbc.execute("set local session_replication_role=origin");
                                        assertThrows(
                                                InconsistentPotFactsException.class,
                                                () ->
                                                        f.commit(
                                                                proposal,
                                                                x ->
                                                                        f.writer
                                                                                .reviseExpenseFinancials(
                                                                                        f.p, x)));
                                        throw new PotHistoryPostgresTest.ForcedRollback();
                                    }));
            assertEquals(before, database());
        }
        f.evidence(
                "F2-references",
                "4 malformed touched identity/reference variants rejected atomically");
    }

    @Test
    void f205GroupedBatchesAndNotLoadedRemainSelective() throws Exception {
        f.history(); // existing literal V1..9 oracle, including multi-creation and deletions
        f.source.sql.clear();
        f.in(
                () -> {
                    f.reader.verifyHistory(f.p);
                    return null;
                });
        assertEquals(f.oracle(7), f.in(() -> f.reader.atVersion(f.p, 7).orElseThrow()));
        // A fresh active fixture to observe the two-target WRITE directly.
        f.freshSchema();
        f.expense();
        var view = f.in(f::current);
        var next =
                f.change(
                        ShareholderTransitions.updateWeights(
                                view.facts(),
                                3,
                                List.of(
                                        new ShareholderTransitions.WeightIntent(f.a, f.two),
                                        new ShareholderTransitions.WeightIntent(f.b, f.three)),
                                f.references(view),
                                f.weights(view)));
        f.source.sql.clear();
        f.write(() -> f.commit(next, x -> f.writer.reviseShareholderWeights(f.p, x)));
        var emitted = List.copyOf(f.source.sql);
        assertEquals(
                1,
                emitted.stream()
                        .filter(
                                s ->
                                        s.startsWith("select c.*")
                                                && s.contains("v3_shareholder_weight c"))
                        .count());
        assertTrue(emitted.stream().noneMatch(s -> s.contains("order by object_id,local_version")));
        assertTrue(
                emitted.stream()
                        .noneMatch(
                                s ->
                                        s.contains("from v3_expense_details")
                                                || s.contains("from v3_expense_financials")));
        f.evidence("F2-grouped-write", emitted);
    }

    @Test
    void f206SameRevisionCannotCloseTwiceUnderActualLockWait() throws Exception {
        f.pot();
        try (var first = f.source.getConnection();
                var second = f.source.getConnection();
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            String close =
                    "update v3_pot_details set active_to=2 where pot_id=? and object_id=? and"
                        + " revision_id=? and local_version=1 and active_from=1 and active_to is"
                        + " null and active_from<2";
            long id = f.jdbc.queryForObject("select revision_id from v3_pot_details", Long.class);
            int pid =
                    new JdbcTemplate(
                                    new org.springframework.jdbc.datasource
                                            .SingleConnectionDataSource(second, true))
                            .queryForObject("select pg_backend_pid()", Integer.class);
            try (var a = first.prepareStatement(close);
                    var b = second.prepareStatement(close)) {
                for (var statement : List.of(a, b)) {
                    statement.setObject(1, f.p.value());
                    statement.setObject(2, f.p.value());
                    statement.setLong(3, id);
                }
                assertEquals(1, a.executeUpdate());
                var started = new CountDownLatch(1);
                var waiting =
                        pool.submit(
                                () -> {
                                    started.countDown();
                                    return b.executeUpdate();
                                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean blocked = false;
                while (System.nanoTime() < limit && !blocked) {
                    blocked =
                            Boolean.TRUE.equals(
                                    f.jdbc.queryForObject(
                                            "select cardinality(pg_blocking_pids(?))>0",
                                            Boolean.class,
                                            pid));
                    Thread.onSpinWait();
                }
                assertTrue(blocked, "second closure must actually wait on first row lock");
                // Complete this explicit low-level fixture on the same held connection.
                // The full C protocol/engine concurrency is independently covered by c02..c09.
                try (var v =
                                first.prepareStatement(
                                        "insert into v3_pot_version(pot_id,version,recorded_at)"
                                                + " values (?,2,clock_timestamp())");
                        var revision =
                                first.prepareStatement(
                                        "insert into"
                                            + " v3_pot_details(pot_id,object_id,local_version,active_from,label,creator_id)"
                                            + " values (?,?,2,2,'winner',?)")) {
                    v.setObject(1, f.p.value());
                    v.executeUpdate();
                    revision.setObject(1, f.p.value());
                    revision.setObject(2, f.p.value());
                    revision.setObject(3, f.creator.value());
                    revision.executeUpdate();
                }
                first.commit();
                assertEquals(0, waiting.get(10, TimeUnit.SECONDS));
                second.rollback();
            } finally {
                first.rollback();
                second.rollback();
            }
        }
        f.in(
                () -> {
                    f.reader.verifyHistory(f.p);
                    return null;
                });
        assertEquals(
                2L, f.jdbc.queryForObject("select max(version) from v3_pot_version", Long.class));
        f.evidence(
                "F2-close-race",
                "actual pg_blocking_pids wait; first close=1, second=0; one complete successor;"
                        + " audit PASS");
    }

    @Test
    void f207EffectIndexAvoidsScanningOldRevisions() throws Exception {
        f.pot();
        f.write(
                () -> {
                    f.jdbc.update("update v3_pot_details set active_to=2 where active_to is null");
                    f.jdbc.update(
                            "insert into v3_pot_version(pot_id,version,recorded_at) select"
                                + " ?,v,clock_timestamp() from generate_series(2,10000) v",
                            f.p.value());
                    f.jdbc.update(
                            "insert into"
                                + " v3_pot_details(pot_id,object_id,local_version,active_from,active_to,label,creator_id)"
                                + " select ?,?,v,v,case when v=10000 then null else v+1"
                                + " end,'L'||v,? from generate_series(2,10000) v",
                            f.p.value(),
                            f.p.value(),
                            f.creator.value());
                });
        f.jdbc.execute("analyze v3_pot_details");
        String sql =
                "explain (analyze,buffers,format json) select object_id,local_version from"
                    + " v3_pot_details where pot_id=? and active_from=?";
        String indexed = f.jdbc.queryForObject(sql, String.class, f.p.value(), 10000L);
        assertTrue(indexed.contains("v3_pot_details_effect"), indexed);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var plan = mapper.readTree(indexed).get(0).get("Plan");
        assertEquals(1, plan.get("Actual Rows").asInt());
        assertTrue(
                plan.path("Shared Hit Blocks").asInt() + plan.path("Shared Read Blocks").asInt()
                        <= 8,
                indexed);
        var unindexed = new String[1];
        assertThrows(
                PotHistoryPostgresTest.ForcedRollback.class,
                () ->
                        f.write(
                                () -> {
                                    f.jdbc.execute("drop index v3_pot_details_effect");
                                    unindexed[0] =
                                            f.jdbc.queryForObject(
                                                    sql, String.class, f.p.value(), 10000L);
                                    throw new PotHistoryPostgresTest.ForcedRollback();
                                }));
        var old = mapper.readTree(unindexed[0]).get(0).get("Plan");
        assertTrue(
                old.path("Shared Hit Blocks").asInt() + old.path("Shared Read Blocks").asInt() > 8,
                unindexed[0]);
        assertEquals(
                8,
                f.jdbc.queryForObject(
                        "select count(*) from pg_indexes where schemaname=? and indexname like"
                            + " 'v3_%_effect'",
                        Integer.class, f.schema));
        f.evidence(
                "F2-effect-index",
                Map.of(
                        "indexed",
                        mapper.readTree(indexed),
                        "without_effect_index",
                        mapper.readTree(unindexed[0])));
    }
}
