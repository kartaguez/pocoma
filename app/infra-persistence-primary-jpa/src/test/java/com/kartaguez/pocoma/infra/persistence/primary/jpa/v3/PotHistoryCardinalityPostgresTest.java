package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.pot.v3.InconsistentPotFactsException;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.v3.PotHistoryPort.Selection;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.*;
import java.util.stream.*;

/** Fixed JDBC arity, independent of retained historical identities; no capacity/SLO claim. */
@Testcontainers
class PotHistoryCardinalityPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = PotHistoryPostgresTest.POSTGRES;
    PotHistoryPostgresTest f;

    @BeforeEach
    void setup() {
        f = new PotHistoryPostgresTest();
        f.freshSchema();
    }

    @Test
    void representativeHistorySelectionAndFinancialsRetainLiteralOracles() {
        f.history();
        f.source.sql.clear();
        for (long version = 1; version <= 9; version++) {
            final long v = version;
            var expected = f.oracle(v);
            assertEquals(expected, f.in(() -> f.reader.atVersion(f.p, v).orElseThrow()));
            var financial = f.in(() -> f.reader.financialAtVersion(f.p, v).orElseThrow());
            expected.expenses()
                    .forEach(
                            (id, expense) ->
                                    assertEquals(
                                            expense.financials(),
                                            financial.expenses().get(id).financials()));
        }
        var selection = new Selection(Set.of(f.a), Set.of(f.e), false, true, true, true, true);
        var partial =
                f.in(
                        () ->
                                f.reader
                                        .componentsAtVersion(f.p, 5, f.creator, selection)
                                        .orElseThrow());
        assertEquals(Set.of(f.a), partial.shareholders().keySet());
        assertEquals(
                f.oracle(5).expenses().get(f.e).financials(),
                f.present(partial.expenses().get(f.e).financials()));
        assertEquals(f.oracle(9).pot().header(), f.in(f::current).facts().header());
        f.in(
                () -> {
                    f.reader.verifyHistory(f.p);
                    return null;
                });
        var emitted = List.copyOf(f.source.sql);
        assertTrue(emitted.stream().anyMatch(q -> q.contains("financial_revision_id = any(?)")));
        assertTrue(emitted.stream().anyMatch(q -> q.contains("object_id = any(?)")));
        assertTrue(emitted.stream().noneMatch(q -> q.contains(" in (")), emitted.toString());
        assertTrue(emitted.stream().allMatch(q -> q.chars().filter(c -> c == '?').count() <= 5));
    }

    @Test
    void uuidAndBigintArraysCrossProtocolBoundaryWithFixedArity() {
        long started = System.nanoTime();
        f.in(
                () -> {
                    // SQL-mechanics fixture only: 65,536 real rows, no business commands or trigger
                    // campaign.
                    f.jdbc.update(
                            "create temporary table v3_arity_identity on commit drop as select"
                                + " ?::uuid as pot_id, md5(n::text)::uuid as object_id, 1::bigint"
                                + " as created_at from generate_series(1,65536) n",
                            f.p.value());
                    f.jdbc.execute(
                            "create temporary table v3_arity_component on commit drop as select"
                                + " row_number() over ()::bigint as revision_id, pot_id,object_id,"
                                + " 1::bigint as local_version,1::bigint as"
                                + " active_from,null::bigint as active_to from v3_arity_identity");
                    f.jdbc.execute(
                            "create unique index on"
                                    + " v3_arity_component(pot_id,object_id,local_version)");
                    var rows = new PotHistoryRows(f.source);
                    f.source.sql.clear();
                    var births = rows.identities("arity", f.p, null);
                    assertEquals(65536, births.size());
                    var ids = births.keySet();
                    assertEquals(births, rows.identities("arity", f.p, ids, 1L));
                    assertEquals(
                            ids, rows.component("arity_component", f.p, 1, 1, births).keySet());
                    assertEquals(
                            ids, rows.component("arity_component", f.p, 1, 2, births).keySet());
                    assertEquals(
                            ids, rows.openComponent("arity_component", f.p, 1, births).keySet());
                    assertEquals(
                            ids,
                            rows.checkedComponent("arity_component", f.p, 1, 1, births).keySet());
                    // Exact same BIGINT binding used by financials, with one wire parameter, not
                    // 65,536.
                    var revisions = LongStream.rangeClosed(1, 65536).boxed().toArray();
                    var named = new NamedParameterJdbcTemplate(f.source);
                    assertEquals(
                            65536L,
                            named.queryForObject(
                                    "select count(*) from v3_arity_component where revision_id ="
                                            + " any(:ids)",
                                    Map.of("ids", new SqlArrayValue("bigint", revisions)),
                                    Long.class));
                    assertTrue(f.source.sql.stream().noneMatch(q -> q.contains(" in (")));
                    assertTrue(
                            f.source.sql.stream()
                                    .allMatch(q -> q.chars().filter(c -> c == '?').count() <= 5));
                    // Old expansion deterministically fails at bind preparation, on the same real
                    // driver.
                    var oldExpansion =
                            assertThrows(
                                    org.springframework.dao.DataAccessException.class,
                                    () ->
                                            named.queryForObject(
                                                    "select 1 where 1 in (:ids)",
                                                    Map.of("ids", Arrays.asList(revisions)),
                                                    Integer.class));
                    System.out.println(
                            "OLD_EXPANSION_CAUSE " + oldExpansion.getMostSpecificCause());
                    assertTrue(
                            oldExpansion
                                    .getMostSpecificCause()
                                    .getMessage()
                                    .replaceAll("\\D", "")
                                    .contains("65535"));
                    return null;
                });
        System.out.println(
                "ARITY_BOUNDARY rows=65536 elapsed_ms="
                        + (System.nanoTime() - started) / 1_000_000);
    }

    @Test
    void selectionExclusionAndIntegrityFailureStillRollBackPhysicalTransaction() {
        f.history();
        var before = f.intervals();
        assertThrows(
                InconsistentPotFactsException.class,
                () ->
                        f.in(
                                () -> {
                                    f.jdbc.update(
                                            "delete from v3_expense_details where local_version=2");
                                    return f.reader.componentsAtVersion(f.p, 4, f.creator, f.all());
                                }));
        assertEquals(before, f.intervals());
        assertEquals(f.oracle(4), f.in(() -> f.reader.atVersion(f.p, 4).orElseThrow()));
        f.in(
                () -> {
                    var rows = new PotHistoryRows(f.source);
                    var subset = rows.identities("shareholder", f.p, Set.of(f.a.value()), 1L);
                    assertTrue(subset.isEmpty(), "identity not yet born must stay excluded");
                    assertTrue(
                            rows.identities("expense", f.p, Set.of(UUID.randomUUID()), 9L)
                                    .isEmpty());
                    assertTrue(rows.component("expense_details", f.p, 9, 9, Map.of()).isEmpty());
                    return null;
                });
    }
}
