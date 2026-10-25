package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import static com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.PotHistoryRows.*;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.v3.PotRevisionPort;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import javax.sql.DataSource;

/** Caller owns version arbitration, request provenance and commit (L1-C). */
public final class JdbcPotRevisionAdapter implements PotRevisionPort {
    private final PotHistoryRows rows;

    public JdbcPotRevisionAdapter(DataSource dataSource) {
        rows = new PotHistoryRows(dataSource);
    }

    @Override
    public void createPot(TransitionResult.Changed<PotState> proposal, Instant recordedAt) {
        rows.requireTransaction(false);
        Objects.requireNonNull(recordedAt);
        var state = proposal.changes();
        var pot = state.header().value().potId();
        if (proposal.expectedPotVersion() != 0
                || proposal.candidatePotVersion() != 1
                || state.header().value().deleted()) throw bad("Invalid initial Pot proposal");
        initial(state.header(), 1);
        initial(state.details(), 1);
        rows.jdbc.update("insert into v3_pot_identity(pot_id) values (?)", pot.value());
        rows.jdbc.update(
                "insert into v3_pot_version(pot_id,version,recorded_at) values (?,1,?)",
                pot.value(),
                Timestamp.from(recordedAt));
        insertPotHeader(state.header());
        insertPotDetails(state.details());
    }

    @Override
    public void recordVersion(PotId pot, long expected, long candidate, Instant recordedAt) {
        rows.requireTransaction(false);
        Objects.requireNonNull(recordedAt);
        if (expected < 1 || Math.incrementExact(expected) != candidate)
            throw bad("Invalid version step");
        var versions = rows.versions(pot);
        if (versions.isEmpty() || number(versions.getLast(), "version") != expected)
            throw bad("Unexpected current Pot version");
        var header =
                rows.component("pot_header", pot, expected, expected, Map.of(pot.value(), 1L))
                        .get(pot.value());
        if ((boolean) header.get("deleted")) throw bad("Terminal Pot cannot advance");
        rows.jdbc.update(
                "insert into v3_pot_version(pot_id,version,recorded_at) values (?,?,?)",
                pot.value(),
                candidate,
                Timestamp.from(recordedAt));
    }

    private void proposal(PotId pot, TransitionResult.Changed<?> proposal) {
        rows.requireTransaction(false);
        var versions = rows.versions(pot);
        if (versions.isEmpty()
                || number(versions.getLast(), "version") != proposal.candidatePotVersion())
            throw bad("Candidate metadata missing");
        // xmin is PostgreSQL's physical inserting transaction, never a domain version.
        // Compare the low 32 bits of xid8 to support transaction-id wraparound without overflow.
        Boolean owned =
                rows.jdbc.queryForObject(
                        "select xmin::text::numeric ="
                            + " mod(pg_current_xact_id()::text::numeric,4294967296) from"
                            + " v3_pot_version where pot_id=? and version=?",
                        Boolean.class,
                        pot.value(),
                        proposal.candidatePotVersion());
        if (!Boolean.TRUE.equals(owned))
            throw bad("Candidate metadata belongs to another physical transaction");
    }

    @Override
    public void createShareholders(
            PotId pot, TransitionResult.Changed<List<ShareholderState>> proposal) {
        proposal(pot, proposal);
        if (proposal.changes().isEmpty()) throw bad("Empty creation");
        var ids = new HashSet<ShareholderId>();
        for (var state : proposal.changes()) {
            samePot(pot, state.header().value().potId());
            if (!ids.add(state.header().value().shareholderId())
                    || state.header().value().deleted()) throw bad("Invalid shareholder creation");
            initial(state.header(), proposal.candidatePotVersion());
            initial(state.details(), proposal.candidatePotVersion());
            initial(state.weight(), proposal.candidatePotVersion());
        }
        for (var state : proposal.changes()) {
            rows.jdbc.update(
                    "insert into v3_shareholder_identity(pot_id,object_id,created_at) values"
                            + " (?,?,?)",
                    pot.value(),
                    state.header().value().shareholderId().value(),
                    proposal.candidatePotVersion());
            insertShareholderHeader(state.header());
            insertShareholderDetails(state.details());
            insertShareholderWeight(state.weight());
        }
    }

    @Override
    public void createExpense(PotId pot, TransitionResult.Changed<ExpenseState> proposal) {
        proposal(pot, proposal);
        var state = proposal.changes();
        samePot(pot, state.header().value().potId());
        if (state.header().value().deleted()) throw bad("Invalid Expense creation");
        initial(state.header(), proposal.candidatePotVersion());
        initial(state.details(), proposal.candidatePotVersion());
        initial(state.financials(), proposal.candidatePotVersion());
        financialReferences(pot, state.financials().value(), proposal);
        rows.jdbc.update(
                "insert into v3_expense_identity(pot_id,object_id,created_at) values (?,?,?)",
                pot.value(),
                state.header().value().expenseId().value(),
                proposal.candidatePotVersion());
        insertExpenseHeader(state.header());
        insertExpenseDetails(state.details());
        insertExpenseFinancials(state.financials());
    }

    @Override
    public void revisePotDetails(
            PotId pot, TransitionResult.Changed<Versioned<PotDetails>> proposal) {
        proposal(pot, proposal);
        var next = proposal.changes();
        samePot(pot, next.value().potId());
        var old = check("pot_details", pot, pot.value(), next, proposal);
        if (!uuid(old, "creator_id").equals(next.value().creatorId().value()))
            throw bad("Creator must remain immutable");
        close("pot_details", pot, pot.value(), next);
        insertPotDetails(next);
    }

    @Override
    public void revisePotHeader(
            PotId pot, TransitionResult.Changed<Versioned<PotHeader>> proposal) {
        proposal(pot, proposal);
        var next = proposal.changes();
        samePot(pot, next.value().potId());
        deletion("pot_header", pot, pot.value(), next, next.value().deleted(), proposal);
        close("pot_header", pot, pot.value(), next);
        insertPotHeader(next);
    }

    @Override
    public void reviseShareholderDetails(
            PotId pot,
            TransitionResult.Changed<Map<ShareholderId, Versioned<ShareholderDetails>>> proposal) {
        proposal(pot, proposal);
        for (var entry : proposal.changes().entrySet()) {
            var next = entry.getValue();
            samePot(pot, next.value().potId());
            if (!entry.getKey().equals(next.value().shareholderId()))
                throw bad("Mixed details key");
            check("shareholder_details", pot, entry.getKey().value(), next, proposal);
        }
        for (var entry : proposal.changes().entrySet()) {
            close("shareholder_details", pot, entry.getKey().value(), entry.getValue());
            insertShareholderDetails(entry.getValue());
        }
    }

    @Override
    public void reviseShareholderWeights(
            PotId pot,
            TransitionResult.Changed<Map<ShareholderId, Versioned<ShareholderWeight>>> proposal) {
        proposal(pot, proposal);
        for (var entry : proposal.changes().entrySet()) {
            var next = entry.getValue();
            samePot(pot, next.value().potId());
            if (!entry.getKey().equals(next.value().shareholderId())) throw bad("Mixed weight key");
            check("shareholder_weight", pot, entry.getKey().value(), next, proposal);
        }
        for (var entry : proposal.changes().entrySet()) {
            close("shareholder_weight", pot, entry.getKey().value(), entry.getValue());
            insertShareholderWeight(entry.getValue());
        }
    }

    @Override
    public void reviseShareholderHeader(
            PotId pot, TransitionResult.Changed<Versioned<ShareholderHeader>> proposal) {
        proposal(pot, proposal);
        var next = proposal.changes();
        samePot(pot, next.value().potId());
        deletion(
                "shareholder_header",
                pot,
                next.value().shareholderId().value(),
                next,
                next.value().deleted(),
                proposal);
        close("shareholder_header", pot, next.value().shareholderId().value(), next);
        insertShareholderHeader(next);
    }

    @Override
    public void reviseExpenseDetails(
            PotId pot, TransitionResult.Changed<Versioned<ExpenseDetails>> proposal) {
        proposal(pot, proposal);
        var next = proposal.changes();
        samePot(pot, next.value().potId());
        check("expense_details", pot, next.value().expenseId().value(), next, proposal);
        close("expense_details", pot, next.value().expenseId().value(), next);
        insertExpenseDetails(next);
    }

    @Override
    public void reviseExpenseFinancials(
            PotId pot, TransitionResult.Changed<Versioned<ExpenseFinancials>> proposal) {
        proposal(pot, proposal);
        var next = proposal.changes();
        samePot(pot, next.value().potId());
        check("expense_financials", pot, next.value().expenseId().value(), next, proposal);
        financialReferences(pot, next.value(), proposal);
        close("expense_financials", pot, next.value().expenseId().value(), next);
        insertExpenseFinancials(next);
    }

    @Override
    public void reviseExpenseHeader(
            PotId pot, TransitionResult.Changed<Versioned<ExpenseHeader>> proposal) {
        proposal(pot, proposal);
        var next = proposal.changes();
        samePot(pot, next.value().potId());
        deletion(
                "expense_header",
                pot,
                next.value().expenseId().value(),
                next,
                next.value().deleted(),
                proposal);
        close("expense_header", pot, next.value().expenseId().value(), next);
        insertExpenseHeader(next);
    }

    private Map<String, Object> check(
            String table,
            PotId pot,
            UUID id,
            Versioned<?> next,
            TransitionResult.Changed<?> proposal) {
        if (next.activeFrom() != proposal.candidatePotVersion())
            throw bad("Wrong applicability start");
        var births =
                table.startsWith("pot_")
                        ? Map.of(id, 1L)
                        : rows.identities(
                                table.startsWith("shareholder_") ? "shareholder" : "expense",
                                pot,
                                Set.of(id));
        if (!births.containsKey(id)) throw bad("Missing revision identity");
        var old =
                rows.component(
                                table,
                                pot,
                                proposal.expectedPotVersion(),
                                proposal.candidatePotVersion(),
                                births)
                        .get(id);
        if (old == null
                || Math.incrementExact(number(old, "local_version")) != next.localVersion()
                || old.get("active_to") != null) throw bad("Unexpected local revision");
        return old;
    }

    private void deletion(
            String table,
            PotId pot,
            UUID id,
            Versioned<?> next,
            boolean deleted,
            TransitionResult.Changed<?> proposal) {
        var old = check(table, pot, id, next, proposal);
        if (!deleted || (boolean) old.get("deleted"))
            throw bad("Header deletion must be effective");
    }

    private void close(String table, PotId pot, UUID id, Versioned<?> next) {
        int count =
                rows.jdbc.update(
                        "update v3_"
                                + table
                                + " set active_to=? where pot_id=? and object_id=? and"
                                + " local_version=? and active_to is null and active_from<?",
                        next.activeFrom(),
                        pot.value(),
                        id,
                        Math.decrementExact(next.localVersion()),
                        next.activeFrom());
        if (count != 1) throw bad("Revision close conflict");
    }

    private void financialReferences(
            PotId pot, ExpenseFinancials financials, TransitionResult.Changed<?> proposal) {
        var ids = new HashSet<UUID>();
        ids.add(financials.payerId().value());
        financials.participants().keySet().forEach(id -> ids.add(id.value()));
        var births = rows.identities("shareholder", pot, ids);
        var headers =
                rows.component(
                        "shareholder_header",
                        pot,
                        proposal.expectedPotVersion(),
                        proposal.candidatePotVersion(),
                        births);
        if (!headers.keySet().equals(ids)
                || headers.values().stream().anyMatch(row -> (boolean) row.get("deleted")))
            throw bad("Invalid financial reference");
    }

    private void initial(Versioned<?> revision, long version) {
        if (revision.localVersion() != 1 || revision.activeFrom() != version)
            throw bad("Invalid initial revision");
    }

    private void samePot(PotId expected, PotId actual) {
        if (!expected.equals(actual)) throw bad("Mixed Pot proposal");
    }

    private void insert(
            String table,
            PotId pot,
            UUID id,
            Versioned<?> revision,
            String columns,
            Object... values) {
        var args =
                new ArrayList<Object>(
                        List.of(pot.value(), id, revision.localVersion(), revision.activeFrom()));
        args.addAll(Arrays.asList(values));
        String placeholders = String.join(",", Collections.nCopies(args.size(), "?"));
        rows.jdbc.update(
                "insert into v3_"
                        + table
                        + "(pot_id,object_id,local_version,active_from,"
                        + columns
                        + ") values ("
                        + placeholders
                        + ")",
                args.toArray());
    }

    private void insertPotHeader(Versioned<PotHeader> revision) {
        var v = revision.value();
        insert("pot_header", v.potId(), v.potId().value(), revision, "deleted", v.deleted());
    }

    private void insertPotDetails(Versioned<PotDetails> revision) {
        var v = revision.value();
        insert(
                "pot_details",
                v.potId(),
                v.potId().value(),
                revision,
                "label,creator_id",
                v.label().value(),
                v.creatorId().value());
    }

    private void insertShareholderHeader(Versioned<ShareholderHeader> revision) {
        var v = revision.value();
        insert(
                "shareholder_header",
                v.potId(),
                v.shareholderId().value(),
                revision,
                "deleted",
                v.deleted());
    }

    private void insertShareholderDetails(Versioned<ShareholderDetails> revision) {
        var v = revision.value();
        insert(
                "shareholder_details",
                v.potId(),
                v.shareholderId().value(),
                revision,
                "name,user_id",
                v.name().value(),
                v.userId().map(id -> id.value()).orElse(null));
    }

    private void insertShareholderWeight(Versioned<ShareholderWeight> revision) {
        var v = revision.value();
        insert(
                "shareholder_weight",
                v.potId(),
                v.shareholderId().value(),
                revision,
                "numerator,denominator",
                v.defaultWeight().value().numerator().toString(),
                v.defaultWeight().value().denominator().toString());
    }

    private void insertExpenseHeader(Versioned<ExpenseHeader> revision) {
        var v = revision.value();
        insert(
                "expense_header",
                v.potId(),
                v.expenseId().value(),
                revision,
                "deleted",
                v.deleted());
    }

    private void insertExpenseDetails(Versioned<ExpenseDetails> revision) {
        var v = revision.value();
        insert(
                "expense_details",
                v.potId(),
                v.expenseId().value(),
                revision,
                "label,expense_date",
                v.label().value(),
                java.sql.Date.valueOf(v.date()));
    }

    private void insertExpenseFinancials(Versioned<ExpenseFinancials> revision) {
        var v = revision.value();
        insert(
                "expense_financials",
                v.potId(),
                v.expenseId().value(),
                revision,
                "numerator,denominator,payer_id",
                v.amount().value().numerator().toString(),
                v.amount().value().denominator().toString(),
                v.payerId().value());
        var key =
                rows.jdbc.queryForObject(
                        "select revision_id from v3_expense_financials where pot_id=? and"
                                + " object_id=? and local_version=?",
                        Long.class,
                        v.potId().value(),
                        v.expenseId().value(),
                        revision.localVersion());
        for (var participant : v.participants().entrySet())
            rows.jdbc.update(
                    "insert into"
                        + " v3_expense_participant(pot_id,financial_revision_id,shareholder_id,numerator,denominator)"
                        + " values (?,?,?,?,?)",
                    v.potId().value(),
                    key,
                    participant.getKey().value(),
                    participant.getValue().map(w -> w.value().numerator().toString()).orElse(null),
                    participant
                            .getValue()
                            .map(w -> w.value().denominator().toString())
                            .orElse(null));
    }
}
