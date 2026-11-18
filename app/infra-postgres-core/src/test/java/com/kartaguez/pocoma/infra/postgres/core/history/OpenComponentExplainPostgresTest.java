package com.kartaguez.pocoma.infra.postgres.core.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class OpenComponentExplainPostgresTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("pocoma_write")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Test
    void explainAnalyzeAndSemanticsOnLongHistoryPot() {
        var ds =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                """
                create table v3_pot_header (
                    revision_id bigint generated always as identity primary key,
                    pot_id uuid not null,
                    object_id uuid not null,
                    local_version bigint not null check(local_version >= 1),
                    active_from bigint not null check(active_from >= 1),
                    active_to bigint check(active_to > active_from),
                    deleted boolean not null default false,
                    unique(pot_id, object_id, local_version),
                    unique(pot_id, revision_id)
                );
                create unique index v3_pot_header_open on v3_pot_header(pot_id, object_id) where active_to is null;
                create index v3_pot_header_at_version on v3_pot_header(pot_id, object_id, active_from, active_to);
                create index v3_pot_header_interval on v3_pot_header using gist (int8range(active_from, active_to, '[)'));
                """);

        UUID targetPot = UUID.randomUUID();
        // Insert 2,000 closed revisions + 1 open revision for targetPot
        jdbc.update(
                """
                insert into v3_pot_header (pot_id, object_id, local_version, active_from, active_to, deleted)
                select ?, ?, g, g, case when g = 2001 then null else g + 1 end, false
                from generate_series(1, 2001) as g
                """,
                targetPot,
                targetPot);
        // Insert 100 other pots with 20 revisions each (2,000 more rows)
        for (int i = 0; i < 100; i++) {
            UUID other = UUID.randomUUID();
            jdbc.update(
                    """
                    insert into v3_pot_header (pot_id, object_id, local_version, active_from, active_to, deleted)
                    select ?, ?, g, g, case when g = 20 then null else g + 1 end, false
                    from generate_series(1, 20) as g
                    """,
                    other,
                    other);
        }
        jdbc.execute("analyze v3_pot_header");

        var named = new NamedParameterJdbcTemplate(ds);
        var params =
                Map.of(
                        "pot",
                        targetPot,
                        "ids",
                        new SqlArrayValue("uuid", new Object[] {targetPot}));

        String oldSql =
                "explain (analyze, buffers) "
                        + "select c.*,f.active_from as first_from,f.active_to as first_to,"
                        + " p.active_from as previous_from,p.active_to as previous_to,"
                        + " l.local_version as last_local,"
                        + " exists(select 1 from v3_pot_header o where o.pot_id=c.pot_id"
                        + " and o.object_id=c.object_id and o.revision_id<>c.revision_id"
                        + " and int8range(o.active_from,o.active_to,'[)')"
                        + " && int8range(c.active_from,null,'[)')) as overlapping"
                        + " from v3_pot_header c"
                        + " left join v3_pot_header f on f.pot_id=c.pot_id and f.object_id=c.object_id and f.local_version=1"
                        + " left join v3_pot_header p on p.pot_id=c.pot_id and p.object_id=c.object_id and p.local_version=c.local_version-1"
                        + " left join lateral(select local_version from v3_pot_header where pot_id=c.pot_id and object_id=c.object_id order by local_version desc limit 1) l on true"
                        + " where c.pot_id=:pot and c.object_id = any(:ids) and c.active_to is null";

        String newSql =
                "explain (analyze, buffers) "
                        + "select c.*,f.active_from as first_from,f.active_to as first_to,"
                        + " p.active_from as previous_from,p.active_to as previous_to,"
                        + " l.local_version as last_local,"
                        + " exists(select 1 from v3_pot_header o where o.pot_id=c.pot_id"
                        + " and o.object_id=c.object_id and o.revision_id<>c.revision_id"
                        + " and (o.active_to is null or o.active_to>c.active_from))"
                        + " as overlapping"
                        + " from v3_pot_header c"
                        + " left join lateral(select active_from,active_to from v3_pot_header where pot_id=c.pot_id and object_id=c.object_id and local_version=1 limit 1) f on true"
                        + " left join lateral(select active_from,active_to from v3_pot_header where pot_id=c.pot_id and object_id=c.object_id and local_version=c.local_version-1 limit 1) p on true"
                        + " left join lateral(select local_version from v3_pot_header where pot_id=c.pot_id and object_id=c.object_id order by local_version desc limit 1) l on true"
                        + " where c.pot_id=:pot and c.object_id = any(:ids) and c.active_to is null";

        List<String> oldPlan = named.queryForList(oldSql, params, String.class);
        List<String> newPlan = named.queryForList(newSql, params, String.class);
        System.out.println("=== OLD EXPLAIN ANALYZE ===");
        oldPlan.forEach(System.out::println);
        System.out.println("=== NEW EXPLAIN ANALYZE ===");
        newPlan.forEach(System.out::println);

        var rows = new PotHistoryRows(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var loaded =
                tx.execute(
                        s ->
                                rows.openComponent(
                                        "pot_header",
                                        new PotId(targetPot),
                                        2001L,
                                        Map.of(targetPot, 1L)));
        assertEquals(2001L, ((Number) loaded.get(targetPot).get("local_version")).longValue());

        // Semantic check: overlapping closed revision (active_to > c.active_from) must be rejected
        UUID badPot = UUID.randomUUID();
        jdbc.update(
                "insert into v3_pot_header (pot_id, object_id, local_version, active_from, active_to) values (?, ?, 1, 1, 5)",
                badPot,
                badPot);
        jdbc.update(
                "insert into v3_pot_header (pot_id, object_id, local_version, active_from, active_to) values (?, ?, 2, 4, null)",
                badPot,
                badPot);
        assertThrows(
                IllegalStateException.class,
                () ->
                        tx.execute(
                                s ->
                                        rows.openComponent(
                                                "pot_header",
                                                new PotId(badPot),
                                                5L,
                                                Map.of(badPot, 1L))));
    }

    @Test
    void equivalenceBetweenOldAndNewOpenComponentSqlAcrossEdgeCases() {
        var ds =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                """
                create table if not exists v3_pot_header (
                    revision_id bigint generated always as identity primary key,
                    pot_id uuid not null,
                    object_id uuid not null,
                    local_version bigint not null check(local_version >= 1),
                    active_from bigint not null check(active_from >= 1),
                    active_to bigint check(active_to > active_from),
                    deleted boolean not null default false,
                    unique(pot_id, object_id, local_version),
                    unique(pot_id, revision_id)
                );
                create table v3_eq_test (
                    revision_id bigint generated always as identity primary key,
                    pot_id uuid not null,
                    object_id uuid not null,
                    local_version bigint not null,
                    active_from bigint not null,
                    active_to bigint,
                    deleted boolean not null default false,
                    unique(pot_id, object_id, local_version)
                );
                """);

        UUID pot = UUID.randomUUID();
        UUID objSingle = UUID.randomUUID();      // single open revision [1, null)
        UUID objAdjacent = UUID.randomUUID();    // adjacent [1, 5), [5, 10), [10, null)
        UUID objGap = UUID.randomUUID();         // non-overlapping [1, 3), [5, null)
        UUID objOverlap = UUID.randomUUID();     // overlapping [1, 6), [5, null)
        UUID objEqualBound = UUID.randomUUID();  // equal bounds [5, 5) + [3, null)
        UUID objAbsent = UUID.randomUUID();      // not present in table

        // Single open revision
        jdbc.update("insert into v3_eq_test (pot_id, object_id, local_version, active_from, active_to) values (?, ?, 1, 1, null)", pot, objSingle);
        // Adjacent multiple versions
        jdbc.update("insert into v3_eq_test (pot_id, object_id, local_version, active_from, active_to) values (?, ?, 1, 1, 5)", pot, objAdjacent);
        jdbc.update("insert into v3_eq_test (pot_id, object_id, local_version, active_from, active_to) values (?, ?, 2, 5, 10)", pot, objAdjacent);
        jdbc.update("insert into v3_eq_test (pot_id, object_id, local_version, active_from, active_to) values (?, ?, 3, 10, null)", pot, objAdjacent);
        // Non-overlapping gap
        jdbc.update("insert into v3_eq_test (pot_id, object_id, local_version, active_from, active_to) values (?, ?, 1, 1, 3)", pot, objGap);
        jdbc.update("insert into v3_eq_test (pot_id, object_id, local_version, active_from, active_to) values (?, ?, 2, 5, null)", pot, objGap);
        // Overlapping
        jdbc.update("insert into v3_eq_test (pot_id, object_id, local_version, active_from, active_to) values (?, ?, 1, 1, 6)", pot, objOverlap);
        jdbc.update("insert into v3_eq_test (pot_id, object_id, local_version, active_from, active_to) values (?, ?, 2, 5, null)", pot, objOverlap);
        // Equal bounds [5, 5) vs [3, null)
        jdbc.update("insert into v3_eq_test (pot_id, object_id, local_version, active_from, active_to) values (?, ?, 1, 5, 5)", pot, objEqualBound);
        jdbc.update("insert into v3_eq_test (pot_id, object_id, local_version, active_from, active_to) values (?, ?, 2, 3, null)", pot, objEqualBound);

        var named = new NamedParameterJdbcTemplate(ds);
        var params =
                Map.of(
                        "pot",
                        pot,
                        "ids",
                        new SqlArrayValue(
                                "uuid",
                                new Object[] {
                                    objSingle, objAdjacent, objGap, objOverlap, objEqualBound, objAbsent
                                }));

        String oldSql =
                "select c.*,f.active_from as first_from,f.active_to as first_to,"
                        + " p.active_from as previous_from,p.active_to as previous_to,"
                        + " l.local_version as last_local,"
                        + " exists(select 1 from v3_eq_test o where o.pot_id=c.pot_id"
                        + " and o.object_id=c.object_id and o.revision_id<>c.revision_id"
                        + " and int8range(o.active_from,o.active_to,'[)')"
                        + " && int8range(c.active_from,null,'[)')) as overlapping"
                        + " from v3_eq_test c"
                        + " left join v3_eq_test f on f.pot_id=c.pot_id and f.object_id=c.object_id and f.local_version=1"
                        + " left join v3_eq_test p on p.pot_id=c.pot_id and p.object_id=c.object_id and p.local_version=c.local_version-1"
                        + " left join lateral(select local_version from v3_eq_test where pot_id=c.pot_id and object_id=c.object_id order by local_version desc limit 1) l on true"
                        + " where c.pot_id=:pot and c.object_id = any(:ids) and c.active_to is null"
                        + " order by c.object_id, c.revision_id";

        String newSql =
                "select c.*,f.active_from as first_from,f.active_to as first_to,"
                        + " p.active_from as previous_from,p.active_to as previous_to,"
                        + " l.local_version as last_local,"
                        + " exists(select 1 from v3_eq_test o where o.pot_id=c.pot_id"
                        + " and o.object_id=c.object_id and o.revision_id<>c.revision_id"
                        + " and (o.active_to is null or (o.active_to>o.active_from and o.active_to>c.active_from)))"
                        + " as overlapping"
                        + " from v3_eq_test c"
                        + " left join lateral(select active_from,active_to from v3_eq_test where pot_id=c.pot_id and object_id=c.object_id and local_version=1 limit 1) f on true"
                        + " left join lateral(select active_from,active_to from v3_eq_test where pot_id=c.pot_id and object_id=c.object_id and local_version=c.local_version-1 limit 1) p on true"
                        + " left join lateral(select local_version from v3_eq_test where pot_id=c.pot_id and object_id=c.object_id order by local_version desc limit 1) l on true"
                        + " where c.pot_id=:pot and c.object_id = any(:ids) and c.active_to is null"
                        + " order by c.object_id, c.revision_id";

        var oldRows = named.queryForList(oldSql, params);
        var newRows = named.queryForList(newSql, params);
        assertEquals(5, oldRows.size());
        assertEquals(oldRows, newRows);
    }
}
