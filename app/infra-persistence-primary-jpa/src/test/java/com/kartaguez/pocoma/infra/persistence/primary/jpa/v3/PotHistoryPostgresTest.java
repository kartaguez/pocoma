package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.v3.*;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.v3.PotHistoryPort.*;

import jakarta.persistence.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.lang.reflect.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import javax.sql.DataSource;

/**
 * Real fresh PostgreSQL; explicit fixture coordination of B primitives, never a production C path.
 */
@Testcontainers
class PotHistoryPostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static final Instant TIME = Instant.parse("2026-10-25T00:00:00Z");
    static final LocalDate DATE = LocalDate.of(2026, 10, 25);
    final PotId p = new PotId(new UUID(1, 1));
    final ShareholderId a = new ShareholderId(new UUID(2, 1)),
            b = new ShareholderId(new UUID(2, 2)),
            unknown = new ShareholderId(new UUID(2, 3));
    final ExpenseId e = new ExpenseId(new UUID(3, 1));
    final UserId creator = new UserId(new UUID(4, 1)), outsider = new UserId(new UUID(4, 2));
    final Weight one = new Weight(Fraction.ONE),
            two = new Weight(new Fraction(2, 1)),
            three = new Weight(new Fraction(3, 1));
    RecordingDataSource source;
    JdbcTemplate jdbc;
    TransactionTemplate transaction;
    JdbcPotHistoryAdapter reader;
    JdbcPotRevisionAdapter writer;
    Flyway flyway;
    String schema;

    @BeforeEach
    void freshSchema() {
        schema = "v3_pot_" + UUID.randomUUID().toString().replace("-", "");
        var delegate =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());
        source = new RecordingDataSource(delegate);
        jdbc = new JdbcTemplate(source);
        flyway =
                Flyway.configure()
                        .dataSource(source)
                        .defaultSchema(schema)
                        .schemas(schema)
                        .createSchemas(true)
                        .locations("classpath:db/v3-pot/migration")
                        .cleanDisabled(true)
                        .load();
        flyway.migrate();
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reader = new JdbcPotHistoryAdapter(source);
        writer = new JdbcPotRevisionAdapter(source);
    }

    <T> T in(Supplier<T> action) {
        return transaction.execute(status -> action.get());
    }

    void write(Runnable action) {
        in(
                () -> {
                    action.run();
                    return null;
                });
    }

    <T> TransitionResult.Changed<T> change(TransitionResult<T> result) {
        assertInstanceOf(TransitionResult.Changed.class, result);
        @SuppressWarnings("unchecked")
        var proposal = (TransitionResult.Changed<T>) result;
        return proposal;
    }

    <T> void commit(
            TransitionResult.Changed<T> proposal, Consumer<TransitionResult.Changed<T>> apply) {
        writer.recordVersion(
                p,
                proposal.expectedPotVersion(),
                proposal.candidatePotVersion(),
                TIME.plusSeconds(proposal.candidatePotVersion()));
        apply.accept(proposal);
    }

    Selection all() {
        return new Selection(Set.of(a, b), Set.of(e), true, true, true, true, true);
    }

    SelectedPot current() {
        return reader.current(p, creator, all()).orElseThrow();
    }

    <T> Versioned<T> present(ComponentRead<T> read) {
        assertInstanceOf(ComponentRead.Present.class, read);
        @SuppressWarnings("unchecked")
        var value = (ComponentRead.Present<T>) read;
        return value.revision();
    }

    Map<ShareholderId, ShareholderReference> references(SelectedPot view) {
        var map = new HashMap<ShareholderId, ShareholderReference>();
        view.shareholders().forEach((id, state) -> map.put(id, state.reference()));
        return map;
    }

    Map<ShareholderId, Versioned<ShareholderWeight>> weights(SelectedPot view) {
        var map = new HashMap<ShareholderId, Versioned<ShareholderWeight>>();
        view.shareholders()
                .forEach(
                        (id, state) -> {
                            if (state.weight()
                                    instanceof ComponentRead.Present<ShareholderWeight> value)
                                map.put(id, value.revision());
                        });
        return map;
    }

    ExpenseFinancials financials(ExpenseId expense, long amount, ShareholderId payer) {
        return new ExpenseFinancials(
                p,
                expense,
                new Amount(new Fraction(amount, 1)),
                payer,
                Map.of(a, Optional.empty(), b, Optional.of(three)));
    }

    void pot() {
        write(
                () ->
                        writer.createPot(
                                change(PotTransitions.create(p, new Label("Pot"), creator, false)),
                                TIME.plusSeconds(1)));
    }

    void shareholders() {
        pot();
        write(
                () -> {
                    var view = current();
                    var proposal =
                            change(
                                    ShareholderTransitions.create(
                                            view.facts(),
                                            1,
                                            List.of(
                                                    new ShareholderTransitions.Creation(
                                                            a,
                                                            new Name("A"),
                                                            Optional.of(creator),
                                                            one),
                                                    new ShareholderTransitions.Creation(
                                                            b,
                                                            new Name("B"),
                                                            Optional.of(creator),
                                                            two)),
                                            references(view)));
                    commit(proposal, x -> writer.createShareholders(p, x));
                });
    }

    void expense() {
        shareholders();
        write(
                () -> {
                    var view = current();
                    var proposal =
                            change(
                                    ExpenseTransitions.create(
                                            view.facts(),
                                            2,
                                            new ExpenseDetails(p, e, new Label("Expense"), DATE),
                                            financials(e, 100, a),
                                            false,
                                            references(view)));
                    commit(proposal, x -> writer.createExpense(p, x));
                });
    }

    void history() {
        expense();
        write(
                () -> {
                    var view = current();
                    var expense = view.expenses().get(e);
                    commit(
                            change(
                                    ExpenseTransitions.updateDetails(
                                            view.facts(),
                                            3,
                                            present(expense.header()),
                                            present(expense.details()),
                                            new Label("Updated"),
                                            DATE.plusDays(1))),
                            x -> writer.reviseExpenseDetails(p, x));
                });
        write(
                () -> {
                    var view = current();
                    var expense = view.expenses().get(e);
                    commit(
                            change(
                                    ExpenseTransitions.updateFinancials(
                                            view.facts(),
                                            4,
                                            present(expense.header()),
                                            present(expense.financials()),
                                            financials(e, 120, b),
                                            references(view))),
                            x -> writer.reviseExpenseFinancials(p, x));
                });
        write(
                () -> {
                    var view = current();
                    commit(
                            change(
                                    ShareholderTransitions.updateWeights(
                                            view.facts(),
                                            5,
                                            List.of(
                                                    new ShareholderTransitions.WeightIntent(
                                                            a, three)),
                                            references(view),
                                            weights(view))),
                            x -> writer.reviseShareholderWeights(p, x));
                });
        write(
                () -> {
                    var view = current();
                    var details =
                            Map.of(
                                    a,
                                    present(view.shareholders().get(a).details()),
                                    b,
                                    present(view.shareholders().get(b).details()));
                    commit(
                            change(
                                    ShareholderTransitions.updateDetails(
                                            view.facts(),
                                            6,
                                            List.of(
                                                    new ShareholderTransitions.DetailsIntent(
                                                            a,
                                                            new Name("A2"),
                                                            Optional.of(creator)),
                                                    new ShareholderTransitions.DetailsIntent(
                                                            b, new Name("B2"), Optional.empty())),
                                            references(view),
                                            details)),
                            x -> writer.reviseShareholderDetails(p, x));
                });
        write(
                () -> {
                    var view = current();
                    var expense = view.expenses().get(e);
                    var state =
                            new ExpenseState(
                                    present(expense.header()),
                                    present(expense.details()),
                                    present(expense.financials()));
                    commit(
                            change(ExpenseTransitions.delete(view.facts(), 7, state)),
                            x -> writer.reviseExpenseHeader(p, x));
                });
        write(
                () -> {
                    var view = current();
                    commit(
                            change(PotTransitions.delete(view.facts(), 8)),
                            x -> writer.revisePotHeader(p, x));
                });
    }

    /** Literal version thresholds and contents, independent of transition/reader implementation. */
    Snapshot oracle(long v) {
        var pot =
                new PotState(
                        new Versioned<>(v >= 9 ? 2 : 1, v >= 9 ? 9 : 1, new PotHeader(p, v >= 9)),
                        new Versioned<>(1, 1, new PotDetails(p, new Label("Pot"), creator)));
        var shareholders = new HashMap<ShareholderId, ShareholderState>();
        if (v >= 2)
            for (var id : List.of(a, b)) {
                var details =
                        new ShareholderDetails(
                                p,
                                id,
                                new Name(
                                        id.equals(a)
                                                ? (v >= 7 ? "A2" : "A")
                                                : (v >= 7 ? "B2" : "B")),
                                v >= 7 && id.equals(b) ? Optional.empty() : Optional.of(creator));
                boolean changed = v >= 6 && id.equals(a);
                shareholders.put(
                        id,
                        new ShareholderState(
                                new Versioned<>(1, 2, new ShareholderHeader(p, id, false)),
                                new Versioned<>(v >= 7 ? 2 : 1, v >= 7 ? 7 : 2, details),
                                new Versioned<>(
                                        changed ? 2 : 1,
                                        changed ? 6 : 2,
                                        new ShareholderWeight(
                                                p,
                                                id,
                                                changed ? three : id.equals(a) ? one : two))));
            }
        var expenses = new HashMap<ExpenseId, ExpenseState>();
        if (v >= 3)
            expenses.put(
                    e,
                    new ExpenseState(
                            new Versioned<>(
                                    v >= 8 ? 2 : 1,
                                    v >= 8 ? 8 : 3,
                                    new ExpenseHeader(p, e, v >= 8)),
                            new Versioned<>(
                                    v >= 4 ? 2 : 1,
                                    v >= 4 ? 4 : 3,
                                    new ExpenseDetails(
                                            p,
                                            e,
                                            new Label(v >= 4 ? "Updated" : "Expense"),
                                            v >= 4 ? DATE.plusDays(1) : DATE)),
                            new Versioned<>(
                                    v >= 5 ? 2 : 1,
                                    v >= 5 ? 5 : 3,
                                    new ExpenseFinancials(
                                            p,
                                            e,
                                            new Amount(new Fraction(v >= 5 ? 120 : 100, 1)),
                                            v >= 5 ? b : a,
                                            Map.of(
                                                    a,
                                                    Optional.empty(),
                                                    b,
                                                    Optional.of(
                                                            new Weight(new Fraction(3, 1))))))));
        return new Snapshot(p, v, TIME.plusSeconds(v), pot, shareholders, expenses);
    }

    @Test
    void b01FreshSchemaRestartAndExactCatalog() throws Exception {
        var names =
                jdbc.queryForList(
                        "select table_name from information_schema.tables where table_schema=?"
                                + " order by table_name",
                        String.class,
                        schema);
        assertEquals(
                Set.of(
                        "flyway_schema_history",
                        "v3_pot_identity",
                        "v3_pot_version",
                        "v3_shareholder_identity",
                        "v3_expense_identity",
                        "v3_pot_header",
                        "v3_pot_details",
                        "v3_shareholder_header",
                        "v3_shareholder_details",
                        "v3_shareholder_weight",
                        "v3_expense_header",
                        "v3_expense_details",
                        "v3_expense_financials",
                        "v3_expense_participant"),
                Set.copyOf(names));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from flyway_schema_history where version is not null",
                        Integer.class));
        assertEquals(0, flyway.migrate().migrationsExecuted);
        flyway.validate();
        var columns =
                jdbc.queryForList(
                        "select table_name,column_name,data_type,is_nullable from"
                                + " information_schema.columns where table_schema=? order by"
                                + " table_name,ordinal_position",
                        schema);
        assertTrue(
                columns.stream()
                        .filter(
                                row ->
                                        Set.of("numerator", "denominator")
                                                .contains(row.get("column_name")))
                        .allMatch(row -> row.get("data_type").equals("text")));
        var constraints =
                jdbc.queryForList(
                        "select conname,pg_get_constraintdef(oid) definition from pg_constraint"
                                + " where connamespace=?::regnamespace order by conname",
                        schema);
        assertTrue(
                constraints.stream()
                        .noneMatch(
                                row ->
                                        row.get("definition")
                                                .toString()
                                                .contains("ON DELETE CASCADE")));
        var functions =
                jdbc.queryForList(
                        "select p.proname,pg_get_functiondef(p.oid) definition from pg_proc p join"
                            + " pg_namespace n on p.pronamespace=n.oid where n.nspname=? order by"
                            + " p.proname",
                        schema);
        var triggers =
                jdbc.queryForList(
                        "select t.tgname,pg_get_triggerdef(t.oid) definition from pg_trigger t join"
                            + " pg_class c on t.tgrelid=c.oid join pg_namespace n on"
                            + " c.relnamespace=n.oid where n.nspname=? and not t.tgisinternal order"
                            + " by t.tgname",
                        schema);
        assertEquals(2, functions.size());
        assertEquals(4, triggers.size());
        assertTrue(
                triggers.stream()
                        .allMatch(
                                row ->
                                        row.get("definition")
                                                .toString()
                                                .contains("DEFERRABLE INITIALLY DEFERRED")));
        evidence("deferred-catalog", Map.of("functions", functions, "triggers", triggers));
        evidence(
                "catalog",
                Map.of(
                        "postgres",
                        jdbc.queryForObject("select version()", String.class),
                        "tables",
                        names,
                        "columns",
                        columns,
                        "constraints",
                        constraints,
                        "indexes",
                        jdbc.queryForList(
                                "select indexname,indexdef from pg_indexes where schemaname=? order"
                                        + " by indexname",
                                schema)));
    }

    @Test
    void b02ReconstructsV1ThroughV9IndependentlyInRandomOrder() throws Exception {
        history();
        var order = new ArrayList<>(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L));
        Collections.shuffle(order, new Random(731));
        for (long version : order)
            assertEquals(
                    oracle(version),
                    in(() -> reader.atVersion(p, version).orElseThrow()),
                    "V" + version);
        assertEquals(oracle(5), in(() -> reader.atVersion(p, 5).orElseThrow()));
        assertTrue(in(() -> reader.atVersion(p, 9).orElseThrow()).pot().header().value().deleted());
        evidence(
                "D01-A-L",
                Map.of(
                        "order",
                        order,
                        "snapshots",
                        order.stream()
                                .map(v -> in(() -> reader.atVersion(p, v).orElseThrow()))
                                .toList(),
                        "intervals",
                        intervals()));
    }

    @Test
    void b03HalfOpenIntervalsAndOnlyChangedComponents() {
        history();
        assertEquals(
                List.of(
                        Map.of("local_version", 1L, "active_from", 2L, "active_to", 6L),
                        Map.of("local_version", 2L, "active_from", 6L)),
                trimmed("v3_shareholder_weight", a.value()));
        assertEquals(
                List.of(
                        Map.of("local_version", 1L, "active_from", 3L, "active_to", 5L),
                        Map.of("local_version", 2L, "active_from", 5L)),
                trimmed("v3_expense_financials", e.value()));
        assertEquals(
                1,
                in(() -> reader.atVersion(p, 4).orElseThrow())
                        .expenses()
                        .get(e)
                        .financials()
                        .localVersion());
        assertEquals(
                2,
                in(() -> reader.atVersion(p, 5).orElseThrow())
                        .expenses()
                        .get(e)
                        .financials()
                        .localVersion());
        assertEquals(
                1,
                in(() -> reader.atVersion(p, 7).orElseThrow())
                        .expenses()
                        .get(e)
                        .header()
                        .localVersion());
        assertEquals(
                2,
                in(() -> reader.atVersion(p, 8).orElseThrow())
                        .expenses()
                        .get(e)
                        .header()
                        .localVersion());
        assertEquals(
                2,
                jdbc.queryForObject("select count(*) from v3_expense_financials", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from v3_pot_details", Integer.class));
        assertEquals(
                4,
                jdbc.queryForObject("select count(*) from v3_shareholder_details", Integer.class));
    }

    @Test
    void b04ShareholderDeletionD01MRetainsAllOldStates() throws Exception {
        shareholders();
        write(
                () -> {
                    var view = current();
                    commit(
                            change(
                                    ShareholderTransitions.updateWeights(
                                            view.facts(),
                                            2,
                                            List.of(
                                                    new ShareholderTransitions.WeightIntent(
                                                            a, three)),
                                            references(view),
                                            weights(view))),
                            x -> writer.reviseShareholderWeights(p, x));
                });
        var v2 = in(() -> reader.atVersion(p, 2).orElseThrow());
        var v3 = in(() -> reader.atVersion(p, 3).orElseThrow());
        write(
                () -> {
                    var view = current();
                    var usage = reader.activeUsage(p, 3, a);
                    assertFalse(usage.referenced());
                    commit(
                            change(
                                    ShareholderTransitions.delete(
                                            view.facts(),
                                            3,
                                            view.shareholders().get(a).reference(),
                                            usage)),
                            x -> writer.reviseShareholderHeader(p, x));
                });
        var v4 = in(() -> reader.atVersion(p, 4).orElseThrow());
        var state = v4.shareholders().get(a);
        assertEquals(new Versioned<>(2, 4, new ShareholderHeader(p, a, true)), state.header());
        assertEquals(v3.shareholders().get(a).details(), state.details());
        assertEquals(v3.shareholders().get(a).weight(), state.weight());
        assertEquals(v2, in(() -> reader.atVersion(p, 2).orElseThrow()));
        assertEquals(v3, in(() -> reader.atVersion(p, 3).orElseThrow()));
        assertFalse(in(() -> reader.atVersion(p, 1).orElseThrow()).shareholders().containsKey(a));
        assertEquals(
                List.of(
                        Map.of("local_version", 1L, "active_from", 2L, "active_to", 4L),
                        Map.of("local_version", 2L, "active_from", 4L)),
                trimmed("v3_shareholder_header", a.value()));
        assertEquals(
                List.of(Map.of("local_version", 1L, "active_from", 2L)),
                trimmed("v3_shareholder_details", a.value()));
        assertEquals(
                List.of(
                        Map.of("local_version", 1L, "active_from", 2L, "active_to", 3L),
                        Map.of("local_version", 2L, "active_from", 3L)),
                trimmed("v3_shareholder_weight", a.value()));
        in(
                () -> {
                    var view = current();
                    assertInstanceOf(
                            TransitionResult.Rejected.class,
                            ShareholderTransitions.updateWeights(
                                    view.facts(),
                                    4,
                                    List.of(new ShareholderTransitions.WeightIntent(a, two)),
                                    references(view),
                                    weights(view)));
                    return null;
                });
        evidence("D01-M", Map.of("V2", v2, "V3", v3, "V4", v4, "intervals", intervals()));
    }

    @Test
    void b05RollbackAndNoopPreserveMetadataAndIntervals() {
        shareholders();
        var before = in(() -> reader.atVersion(p, 2).orElseThrow());
        assertThrows(
                ForcedRollback.class,
                () ->
                        write(
                                () -> {
                                    var view = current();
                                    commit(
                                            change(
                                                    ShareholderTransitions.updateWeights(
                                                            view.facts(),
                                                            2,
                                                            List.of(
                                                                    new ShareholderTransitions
                                                                            .WeightIntent(
                                                                            a, three)),
                                                            references(view),
                                                            weights(view))),
                                            x -> writer.reviseShareholderWeights(p, x));
                                    throw new ForcedRollback();
                                }));
        assertEquals(before, in(() -> reader.atVersion(p, 2).orElseThrow()));
        assertEquals(2, jdbc.queryForObject("select max(version) from v3_pot_version", Long.class));
        assertEquals(
                List.of(Map.of("local_version", 1L, "active_from", 2L)),
                trimmed("v3_shareholder_weight", a.value()));
        in(
                () -> {
                    var view = current();
                    assertInstanceOf(
                            TransitionResult.Unchanged.class,
                            ShareholderTransitions.updateWeights(
                                    view.facts(),
                                    2,
                                    List.of(new ShareholderTransitions.WeightIntent(a, one)),
                                    references(view),
                                    weights(view)));
                    return null;
                });
        assertEquals(2, jdbc.queryForObject("select count(*) from v3_pot_version", Integer.class));
    }

    @Test
    void b06SelectiveReadsDistinguishAbsentAndNotLoadedAndHistoricalRights() throws Exception {
        history();
        var selection =
                new Selection(Set.of(a, unknown), Set.of(e), false, false, true, false, false);
        source.sql.clear();
        var view = in(() -> reader.current(p, outsider, selection).orElseThrow());
        assertInstanceOf(ComponentRead.NotLoaded.class, view.details());
        assertInstanceOf(ComponentRead.NotLoaded.class, view.expenses().get(e).financials());
        assertInstanceOf(ComponentRead.NotLoaded.class, view.shareholders().get(a).details());
        assertInstanceOf(ComponentRead.Absent.class, view.shareholders().get(unknown).weight());
        assertTrue(view.shareholders().get(unknown).reference().header().isEmpty());
        assertEquals(three, present(view.shareholders().get(a).weight()).value().defaultWeight());
        assertFalse(view.facts().authorization().canMutate());
        assertTrue(
                source.sql.stream()
                        .noneMatch(
                                sql ->
                                        sql.contains("v3_expense_financials")
                                                || sql.contains("v3_expense_participant")
                                                || sql.contains("v3_expense_details")));
        var selectiveSql = List.copyOf(source.sql);
        var old = in(() -> reader.componentsAtVersion(p, 1, creator, all()).orElseThrow());
        assertTrue(old.shareholders().get(a).reference().header().isEmpty());
        assertInstanceOf(ComponentRead.Absent.class, old.expenses().get(e).financials());
        var v5 = in(() -> reader.componentsAtVersion(p, 5, creator, all()).orElseThrow());
        assertEquals(one, present(v5.shareholders().get(a).weight()).value().defaultWeight());
        assertEquals(
                Optional.of(creator), present(v5.shareholders().get(b).details()).value().userId());
        var v7 = in(() -> reader.componentsAtVersion(p, 7, creator, all()).orElseThrow());
        assertEquals(
                Optional.empty(), present(v7.shareholders().get(b).details()).value().userId());
        evidence(
                "selective",
                Map.of(
                        "selection",
                        selection,
                        "sql",
                        selectiveSql,
                        "current",
                        view,
                        "V5",
                        v5,
                        "V7",
                        v7));
    }

    @Test
    void b07ReferenceSearchIsExhaustiveForPayerAndParticipantAndIgnoresDeletedExpenses()
            throws Exception {
        shareholders();
        for (int index = 0; index < 35; index++) {
            final int n = index;
            write(
                    () -> {
                        var view = current();
                        var id = new ExpenseId(new UUID(7, n));
                        var f =
                                new ExpenseFinancials(
                                        p,
                                        id,
                                        new Amount(Fraction.ONE),
                                        a,
                                        Map.of(a, Optional.empty()));
                        commit(
                                change(
                                        ExpenseTransitions.create(
                                                view.facts(),
                                                view.facts().workingVersion(),
                                                new ExpenseDetails(p, id, new Label("bulk"), DATE),
                                                f,
                                                false,
                                                references(view))),
                                x -> writer.createExpense(p, x));
                    });
        }
        long before = 37;
        assertFalse(in(() -> reader.activeUsage(p, before, b)).referenced());
        for (boolean payerOnly : List.of(true, false)) {
            long v = in(() -> current().facts().workingVersion());
            var id = new ExpenseId(new UUID(8, payerOnly ? 1 : 2));
            write(
                    () -> {
                        var view = current();
                        var f =
                                new ExpenseFinancials(
                                        p,
                                        id,
                                        new Amount(Fraction.ONE),
                                        payerOnly ? b : a,
                                        Map.of(payerOnly ? a : b, Optional.empty()));
                        commit(
                                change(
                                        ExpenseTransitions.create(
                                                view.facts(),
                                                v,
                                                new ExpenseDetails(
                                                        p,
                                                        id,
                                                        new Label("last blocking reference"),
                                                        DATE),
                                                f,
                                                false,
                                                references(view))),
                                x -> writer.createExpense(p, x));
                    });
            var usage = in(() -> reader.activeUsage(p, v + 1, b));
            assertTrue(usage.referenced());
            assertTrue(usage.exhaustive());
            var selection = new Selection(Set.of(a, b), Set.of(id), true, true, true, true, true);
            write(
                    () -> {
                        var view = reader.current(p, creator, selection).orElseThrow();
                        var expense = view.expenses().get(id);
                        commit(
                                change(
                                        ExpenseTransitions.delete(
                                                view.facts(),
                                                v + 1,
                                                new ExpenseState(
                                                        present(expense.header()),
                                                        present(expense.details()),
                                                        present(expense.financials())))),
                                x -> writer.reviseExpenseHeader(p, x));
                    });
            assertFalse(in(() -> reader.activeUsage(p, v + 2, b)).referenced());
            assertTrue(in(() -> reader.activeUsage(p, v + 1, b)).referenced());
        }
        evidence(
                "reference-search",
                Map.of(
                        "expenseCount",
                        jdbc.queryForObject(
                                "select count(*) from v3_expense_identity", Integer.class),
                        "sql",
                        List.copyOf(source.sql)));
    }

    @Test
    void b08ExactArbitraryRationalsRoundTripAndOptionalOverride() throws Exception {
        shareholders();
        var huge = BigInteger.ONE.shiftLeft(180).add(BigInteger.ONE);
        var rational = new Fraction(huge, BigInteger.valueOf(7));
        var weight = new Weight(new Fraction(huge, BigInteger.valueOf(11)));
        write(
                () -> {
                    var view = current();
                    var f =
                            new ExpenseFinancials(
                                    p,
                                    e,
                                    new Amount(rational),
                                    a,
                                    Map.of(a, Optional.empty(), b, Optional.of(weight)));
                    commit(
                            change(
                                    ExpenseTransitions.create(
                                            view.facts(),
                                            2,
                                            new ExpenseDetails(p, e, new Label("exact"), DATE),
                                            f,
                                            false,
                                            references(view))),
                            x -> writer.createExpense(p, x));
                });
        var f =
                in(() -> reader.atVersion(p, 3).orElseThrow())
                        .expenses()
                        .get(e)
                        .financials()
                        .value();
        assertEquals(huge, f.amount().value().numerator());
        assertEquals(BigInteger.valueOf(7), f.amount().value().denominator());
        assertEquals(Optional.empty(), f.participants().get(a));
        assertEquals(Optional.of(weight), f.participants().get(b));
        write(
                () -> {
                    var view = current();
                    var intent = new ShareholderTransitions.WeightIntent(a, weight);
                    commit(
                            change(
                                    ShareholderTransitions.updateWeights(
                                            view.facts(),
                                            3,
                                            List.of(intent),
                                            references(view),
                                            weights(view))),
                            x -> writer.reviseShareholderWeights(p, x));
                });
        assertEquals(
                weight,
                in(() -> reader.atVersion(p, 4).orElseThrow())
                        .shareholders()
                        .get(a)
                        .weight()
                        .value()
                        .defaultWeight());
        write(
                () -> {
                    var view = current();
                    var intent =
                            new ShareholderTransitions.WeightIntent(
                                    a, new Weight(Fraction.decimal("0.125")));
                    commit(
                            change(
                                    ShareholderTransitions.updateWeights(
                                            view.facts(),
                                            4,
                                            List.of(intent),
                                            references(view),
                                            weights(view))),
                            x -> writer.reviseShareholderWeights(p, x));
                });
        assertEquals(
                new Fraction(1, 8),
                in(() -> reader.atVersion(p, 5).orElseThrow())
                        .shareholders()
                        .get(a)
                        .weight()
                        .value()
                        .defaultWeight()
                        .value());
        assertEquals(
                f,
                in(() -> reader.atVersion(p, 3).orElseThrow())
                        .expenses()
                        .get(e)
                        .financials()
                        .value());
        write(
                () -> {
                    var view = current();
                    var expense = view.expenses().get(e);
                    var zero =
                            new ExpenseFinancials(
                                    p,
                                    e,
                                    new Amount(Fraction.ZERO),
                                    a,
                                    financials(e, 1, a).participants());
                    commit(
                            change(
                                    ExpenseTransitions.updateFinancials(
                                            view.facts(),
                                            5,
                                            present(expense.header()),
                                            present(expense.financials()),
                                            zero,
                                            references(view))),
                            x -> writer.reviseExpenseFinancials(p, x));
                });
        assertEquals(
                new Fraction(0, 1),
                in(() -> reader.atVersion(p, 6).orElseThrow())
                        .expenses()
                        .get(e)
                        .financials()
                        .value()
                        .amount()
                        .value());
        assertEquals(
                f,
                in(() -> reader.atVersion(p, 3).orElseThrow())
                        .expenses()
                        .get(e)
                        .financials()
                        .value());
        evidence(
                "rationals",
                Map.of(
                        "financials",
                        jdbc.queryForList(
                                "select numerator,denominator from v3_expense_financials"),
                        "participants",
                        jdbc.queryForList(
                                "select shareholder_id,numerator,denominator from"
                                        + " v3_expense_participant")));
    }

    @Test
    void b09RejectsGapsOverlapsMissingComponentsAndNoncanonicalRationals() {
        history();
        for (long boundary : List.of(5L, 7L)) {
            write(
                    () ->
                            jdbc.update(
                                    "update v3_shareholder_weight set active_to=? where object_id=?"
                                            + " and local_version=1",
                                    boundary,
                                    a.value()));
            assertThrows(
                    InconsistentPotFactsException.class, () -> in(() -> reader.atVersion(p, 5)));
            write(
                    () ->
                            jdbc.update(
                                    "update v3_shareholder_weight set active_to=6 where object_id=?"
                                            + " and local_version=1",
                                    a.value()));
        }
        write(
                () ->
                        jdbc.update(
                                "update v3_expense_financials set numerator='240',denominator='2'"
                                        + " where local_version=2"));
        assertThrows(InconsistentPotFactsException.class, () -> in(() -> reader.atVersion(p, 5)));
        write(
                () ->
                        jdbc.update(
                                "update v3_expense_financials set numerator='120',denominator='1'"
                                        + " where local_version=2"));
        write(() -> jdbc.update("delete from v3_expense_details where local_version=2"));
        assertThrows(InconsistentPotFactsException.class, () -> in(() -> reader.atVersion(p, 4)));
    }

    @Test
    void b10DatabaseConstraintsRejectDuplicateOpenCrossPotAndMalformedRationals() {
        expense();
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () ->
                        write(
                                () ->
                                        jdbc.update(
                                                "insert into"
                                                    + " v3_shareholder_weight(pot_id,object_id,local_version,active_from,numerator,denominator)"
                                                    + " values (?,?,2,3,'2','1')",
                                                p.value(),
                                                a.value())));
        for (var pair : List.of(List.of("01", "1"), List.of("1", "0"), List.of("-1", "1")))
            assertThrows(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    () ->
                            write(
                                    () ->
                                            jdbc.update(
                                                    "update v3_shareholder_weight set"
                                                            + " numerator=?,denominator=? where"
                                                            + " object_id=?",
                                                    pair.get(0),
                                                    pair.get(1),
                                                    a.value())));
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () ->
                        write(
                                () ->
                                        jdbc.update(
                                                "update v3_expense_participant set denominator=null"
                                                        + " where numerator is not null")));
        var other = new PotId(new UUID(9, 1));
        write(
                () ->
                        writer.createPot(
                                change(
                                        PotTransitions.create(
                                                other, new Label("Other"), creator, false)),
                                TIME.plusSeconds(1)));
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () ->
                        write(
                                () ->
                                        jdbc.update(
                                                "update v3_expense_financials set pot_id=?",
                                                other.value())));
        assertEquals(oracle(3), in(() -> reader.atVersion(p, 3).orElseThrow()));
    }

    @Test
    void b11SnapshotRemainsCoherentWhileAnotherConnectionCommits() throws Exception {
        shareholders();
        var executor = Executors.newSingleThreadExecutor();
        try {
            in(
                    () -> {
                        var before = reader.atVersion(p, 2).orElseThrow();
                        var future =
                                executor.submit(
                                        () ->
                                                write(
                                                        () -> {
                                                            var view = current();
                                                            commit(
                                                                    change(
                                                                            ShareholderTransitions
                                                                                    .updateWeights(
                                                                                            view
                                                                                                    .facts(),
                                                                                            2,
                                                                                            List.of(
                                                                                                    new ShareholderTransitions
                                                                                                            .WeightIntent(
                                                                                                            a,
                                                                                                            three)),
                                                                                            references(
                                                                                                    view),
                                                                                            weights(
                                                                                                    view))),
                                                                    x ->
                                                                            writer
                                                                                    .reviseShareholderWeights(
                                                                                            p, x));
                                                        }));
                        try {
                            future.get(10, TimeUnit.SECONDS);
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                        assertEquals(before, reader.atVersion(p, 2).orElseThrow());
                        assertEquals(2, current().facts().workingVersion());
                        return null;
                    });
            assertEquals(3, in(() -> current().facts().workingVersion()));
            assertEquals(
                    three,
                    in(() -> reader.atVersion(p, 3).orElseThrow())
                            .shareholders()
                            .get(a)
                            .weight()
                            .value()
                            .defaultWeight());
            evidence(
                    "snapshot-concurrency",
                    Map.of(
                            "V2",
                            in(() -> reader.atVersion(p, 2).orElseThrow()),
                            "V3",
                            in(() -> reader.atVersion(p, 3).orElseThrow())));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void b12GroupedReadCountDoesNotGrowPerExpense() throws Exception {
        expense();
        source.sql.clear();
        in(() -> reader.atVersion(p, 3));
        int small = source.sql.size();
        for (int index = 0; index < 20; index++) {
            final int n = index;
            write(
                    () -> {
                        var view = current();
                        var id = new ExpenseId(new UUID(10, n));
                        commit(
                                change(
                                        ExpenseTransitions.create(
                                                view.facts(),
                                                view.facts().workingVersion(),
                                                new ExpenseDetails(p, id, new Label("bulk"), DATE),
                                                financials(id, 1, a),
                                                false,
                                                references(view))),
                                x -> writer.createExpense(p, x));
                    });
        }
        long v = in(() -> current().facts().workingVersion());
        source.sql.clear();
        var snapshot = in(() -> reader.atVersion(p, v).orElseThrow());
        assertEquals(21, snapshot.expenses().size());
        assertEquals(small, source.sql.size());
        assertEquals(
                1,
                source.sql.stream()
                        .filter(sql -> sql.contains("from v3_expense_participant"))
                        .count());
        evidence(
                "query-count",
                Map.of(
                        "oneExpenseQueries",
                        small,
                        "twentyOneExpenseQueries",
                        source.sql.size(),
                        "sql",
                        List.copyOf(source.sql)));
    }

    @Test
    void b13CallerTransactionAndIsolationAreMandatory() {
        assertThrows(InconsistentPotFactsException.class, () -> reader.atVersion(p, 1));
        assertThrows(
                InconsistentPotFactsException.class,
                () ->
                        writer.createPot(
                                change(PotTransitions.create(p, new Label("Pot"), creator, false)),
                                TIME));
        var weak = new TransactionTemplate(new DataSourceTransactionManager(source));
        assertThrows(
                InconsistentPotFactsException.class,
                () -> weak.execute(status -> reader.atVersion(p, 1)));
        pot();
        assertTrue(in(() -> reader.atVersion(new PotId(new UUID(99, 99)), 1)).isEmpty());
        assertThrows(InconsistentPotFactsException.class, () -> in(() -> reader.atVersion(p, 2)));
        assertThrows(
                InconsistentPotFactsException.class,
                () -> write(() -> writer.recordVersion(p, 1, 3, TIME)));
        assertEquals(1, jdbc.queryForObject("select max(version) from v3_pot_version", Long.class));
    }

    @Test
    void b14SharesJpaPhysicalTransactionAndRollback() throws Exception {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setPackagesToScan(getClass().getPackageName());
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(
                Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.default_schema", schema));
        factory.afterPropertiesSet();
        try {
            var manager = new JpaTransactionManager(factory.getObject());
            manager.setDataSource(source);
            var jpa = new TransactionTemplate(manager);
            jpa.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThrows(
                    ForcedRollback.class,
                    () ->
                            jpa.execute(
                                    status -> {
                                        writer.createPot(
                                                change(
                                                        PotTransitions.create(
                                                                p,
                                                                new Label("Pot"),
                                                                creator,
                                                                false)),
                                                TIME.plusSeconds(1));
                                        var em =
                                                EntityManagerFactoryUtils
                                                        .getTransactionalEntityManager(
                                                                factory.getObject());
                                        assertNotNull(em.find(IdentityProbe.class, p.value()));
                                        em.flush();
                                        assertEquals(
                                                0,
                                                new JdbcTemplate(source.getTargetDataSource())
                                                        .queryForObject(
                                                                "select count(*) from"
                                                                        + " v3_pot_identity",
                                                                Integer.class));
                                        throw new ForcedRollback();
                                    }));
            assertEquals(
                    0, jdbc.queryForObject("select count(*) from v3_pot_identity", Integer.class));
            jpa.execute(
                    status -> {
                        writer.createPot(
                                change(PotTransitions.create(p, new Label("Pot"), creator, false)),
                                TIME.plusSeconds(1));
                        assertEquals(oracle(1), reader.atVersion(p, 1).orElseThrow());
                        return null;
                    });
            assertEquals(oracle(1), in(() -> reader.atVersion(p, 1).orElseThrow()));
            evidence(
                    "jpa-transaction",
                    Map.of(
                            "persisted",
                            in(() -> reader.atVersion(p, 1).orElseThrow()),
                            "physical",
                            in(
                                    () ->
                                            jdbc.queryForMap(
                                                    "select pg_backend_pid()"
                                                            + " pid,txid_current() xid"))));
        } finally {
            factory.destroy();
        }
    }

    @Test
    void b15CorruptReferencesAndMissingParticipantsAreNeverNegativeFacts() {
        expense();
        write(
                () ->
                        jdbc.update(
                                "update v3_shareholder_header set deleted=true where object_id=?",
                                a.value()));
        assertThrows(InconsistentPotFactsException.class, () -> in(() -> reader.atVersion(p, 3)));
        assertThrows(
                InconsistentPotFactsException.class,
                () -> in(() -> reader.current(p, creator, all())));
        assertThrows(
                InconsistentPotFactsException.class, () -> in(() -> reader.activeUsage(p, 3, b)));
        write(
                () ->
                        jdbc.update(
                                "update v3_shareholder_header set deleted=false where object_id=?",
                                a.value()));
        write(() -> jdbc.update("delete from v3_expense_participant"));
        assertThrows(InconsistentPotFactsException.class, () -> in(() -> reader.atVersion(p, 3)));
        assertThrows(
                InconsistentPotFactsException.class, () -> in(() -> reader.activeUsage(p, 3, b)));
        var selective = new Selection(Set.of(a), Set.of(e), false, false, true, false, false);
        assertInstanceOf(
                ComponentRead.NotLoaded.class,
                in(() -> reader.current(p, creator, selective).orElseThrow())
                        .expenses()
                        .get(e)
                        .financials());
    }

    @Test
    void b16LastCreationFailureRollsBackEveryComponentAndVersion() {
        shareholders();
        var other = new PotId(new UUID(11, 1));
        write(
                () ->
                        writer.createPot(
                                change(
                                        PotTransitions.create(
                                                other, new Label("Other"), creator, false)),
                                TIME.plusSeconds(1)));
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () ->
                        write(
                                () -> {
                                    var selection =
                                            new Selection(
                                                    Set.of(unknown, a),
                                                    Set.of(),
                                                    false,
                                                    false,
                                                    false,
                                                    false,
                                                    false);
                                    var view =
                                            reader.current(other, creator, selection).orElseThrow();
                                    var proposal =
                                            change(
                                                    ShareholderTransitions.create(
                                                            view.facts(),
                                                            1,
                                                            List.of(
                                                                    new ShareholderTransitions
                                                                            .Creation(
                                                                            unknown,
                                                                            new Name("first valid"),
                                                                            Optional.empty(),
                                                                            one),
                                                                    new ShareholderTransitions
                                                                            .Creation(
                                                                            a,
                                                                            new Name(
                                                                                    "last identity"
                                                                                        + " collision"),
                                                                            Optional.empty(),
                                                                            one)),
                                                            references(view)));
                                    writer.recordVersion(other, 1, 2, TIME.plusSeconds(2));
                                    writer.createShareholders(other, proposal);
                                }));
        assertTrue(in(() -> reader.atVersion(other, 1).orElseThrow()).shareholders().isEmpty());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select max(version) from v3_pot_version where pot_id=?",
                        Long.class,
                        other.value()));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "select count(*) from v3_shareholder_identity where object_id=?",
                        Integer.class,
                        unknown.value()));
        assertEquals(oracle(2), in(() -> reader.atVersion(p, 2).orElseThrow()));
    }

    @Test
    void b17DeferredIntegrityRejectsEmptyVersionAndIncompleteCreation() {
        pot();
        var failure =
                assertThrows(
                        RuntimeException.class,
                        () -> write(() -> writer.recordVersion(p, 1, 2, TIME.plusSeconds(2))));
        assertSqlState(failure, "23514");
        assertEquals(1, jdbc.queryForObject("select max(version) from v3_pot_version", Long.class));
        failure =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                write(
                                        () ->
                                                jdbc.update(
                                                        "insert into"
                                                            + " v3_shareholder_identity(pot_id,object_id,created_at)"
                                                            + " values (?,?,1)",
                                                        p.value(),
                                                        unknown.value())));
        assertSqlState(failure, "23514");
        assertEquals(
                0,
                jdbc.queryForObject("select count(*) from v3_shareholder_identity", Integer.class));
    }

    private void assertSqlState(Throwable failure, String state) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql) {
                assertEquals(state, sql.getSQLState());
                System.out.println("V3_POT_SQLSTATE " + sql.getSQLState());
                return;
            }
        fail("No PostgreSQL SQLSTATE found", failure);
    }

    @Test
    void b18CommittedVersionCannotReceiveAnotherProposalLater() {
        expense();
        var stale =
                in(
                        () -> {
                            var view = current();
                            return change(
                                    PotTransitions.updateDetails(
                                            view.facts(),
                                            3,
                                            present(view.details()),
                                            new Label("late")));
                        });
        write(
                () -> {
                    var view = current();
                    commit(
                            change(
                                    ShareholderTransitions.updateWeights(
                                            view.facts(),
                                            3,
                                            List.of(
                                                    new ShareholderTransitions.WeightIntent(
                                                            a, three)),
                                            references(view),
                                            weights(view))),
                            x -> writer.reviseShareholderWeights(p, x));
                });
        var before = in(() -> reader.atVersion(p, 4).orElseThrow());
        assertThrows(
                InconsistentPotFactsException.class,
                () -> write(() -> writer.revisePotDetails(p, stale)));
        assertEquals(before, in(() -> reader.atVersion(p, 4).orElseThrow()));
        assertEquals(1, jdbc.queryForObject("select count(*) from v3_pot_details", Integer.class));
    }

    @Entity(name = "V3IdentityProbe")
    @Table(name = "v3_pot_identity")
    public static class IdentityProbe {
        @Id
        @Column(name = "pot_id")
        UUID id;

        protected IdentityProbe() {}
    }

    static final class ForcedRollback extends RuntimeException {}

    List<Map<String, Object>> trimmed(String table, UUID id) {
        return jdbc
                .queryForList(
                        "select local_version,active_from,active_to from "
                                + table
                                + " where object_id=? order by local_version",
                        id)
                .stream()
                .map(
                        row -> {
                            var result = new HashMap<>(row);
                            result.values().removeIf(Objects::isNull);
                            return Map.copyOf(result);
                        })
                .toList();
    }

    Map<String, Object> intervals() {
        var data = new LinkedHashMap<String, Object>();
        for (String table :
                List.of(
                        "pot_header",
                        "pot_details",
                        "shareholder_header",
                        "shareholder_details",
                        "shareholder_weight",
                        "expense_header",
                        "expense_details",
                        "expense_financials",
                        "expense_participant"))
            data.put(table, jdbc.queryForList("select * from v3_" + table));
        return data;
    }

    void evidence(String name, Object value) throws Exception {
        var directory = Path.of("target/v3-pot-evidence");
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve(name + ".txt"), "schema=" + schema + "\n" + value + "\n");
        System.out.println("V3_POT_PROOF " + name + " " + value);
    }

    static final class RecordingDataSource extends DelegatingDataSource {
        final List<String> sql = new CopyOnWriteArrayList<>();

        RecordingDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return record(super.getConnection());
        }

        @Override
        public Connection getConnection(String user, String password) throws SQLException {
            return record(super.getConnection(user, password));
        }

        Connection record(Connection connection) {
            return (Connection)
                    Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("prepareStatement")
                                        && args != null
                                        && args[0] instanceof String query) sql.add(query);
                                try {
                                    return method.invoke(connection, args);
                                } catch (InvocationTargetException failure) {
                                    throw failure.getCause();
                                }
                            });
        }
    }
}
