package com.kartaguez.pocoma.infra.postgres.write;
import com.kartaguez.pocoma.infra.postgres.core.bootstrap.*;
import com.kartaguez.pocoma.infra.postgres.core.consumption.*;
import com.kartaguez.pocoma.infra.postgres.core.history.*;

import static com.kartaguez.pocoma.infra.postgres.core.history.PotHistoryRows.*;

import com.kartaguez.pocoma.domain.pot.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.PotRevisionPort;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import javax.sql.DataSource;

/** Caller owns version arbitration, request provenance and commit (L1-C). */
public final class JdbcPotRevisionAdapter implements PotRevisionPort {
    private record RevisionTarget(
            String table, PotId pot, UUID id, Versioned<?> revision) {}

    private record RevisionColumns(String columns, Object... values) {}

    private final PotHistoryRows rows;

    public JdbcPotRevisionAdapter(DataSource dataSource) {
        rows = new PotHistoryRows(dataSource);
    }

    @Override
    public void createPot(TransitionResult.Changed<PotState> proposal, Instant recordedAt) {
        SpringAttemptTransactions.revision(
                rows.dataSource,
                () -> {
                    rows.requireTransaction(false);
                    Objects.requireNonNull(recordedAt);
                    var state = proposal.changes();
                    var pot = state.header().value().potId();
                    if (!isValidInitialPotProposal(proposal, state)) {
                        throw bad("Invalid initial Pot proposal");
                    }
                    initial(state.header(), 1);
                    initial(state.details(), 1);
                    rows.jdbc.update("insert into v3_pot_identity(pot_id) values (?)", pot.value());
                    rows.jdbc.update(
                            "insert into v3_pot_version(pot_id,version,recorded_at) values (?,1,?)",
                            pot.value(),
                            Timestamp.from(recordedAt));
                    insertPotHeader(state.header());
                    insertPotDetails(state.details());
                });
    }

    private static boolean isValidInitialPotProposal(
            TransitionResult.Changed<PotState> proposal, PotState state) {
        if (proposal.expectedPotVersion() != 0 || proposal.candidatePotVersion() != 1) {
            return false;
        }
        return !state.header().value().deleted();
    }

    @Override
    public void recordVersion(PotId pot, long expected, long candidate, Instant recordedAt) {
        SpringAttemptTransactions.revision(
                rows.dataSource,
                () -> {
                    rows.requireTransaction(false);
                    Objects.requireNonNull(recordedAt);
                    if (expected < 1 || Math.incrementExact(expected) != candidate) {
                        throw bad("Invalid version step");
                    }
                    verifyOpenPotVersion(pot, expected);
                    rows.jdbc.update(
                            "insert into v3_pot_version(pot_id,version,recorded_at) values (?,?,?)",
                            pot.value(),
                            candidate,
                            Timestamp.from(recordedAt));
                });
    }

    private void verifyOpenPotVersion(PotId pot, long expected) {
        var latest = rows.version(pot, null);
        if (latest == null || number(latest, "version") != expected) {
            throw bad("Unexpected current Pot version");
        }
        var header =
                rows.openComponent("pot_header", pot, expected, Map.of(pot.value(), 1L))
                        .get(pot.value());
        if ((boolean) header.get("deleted")) throw bad("Terminal Pot cannot advance");
    }

    private void proposal(PotId pot, TransitionResult.Changed<?> proposal) {
        rows.requireTransaction(false);
        // Full xid8 identity remains distinct across 32-bit transaction-id epochs.
        var latest =
                rows.jdbc.queryForList(
                        "select version,writer_xid=pg_current_xact_id() as owned from"
                                + " v3_pot_version where pot_id=? order by version desc limit 1",
                        pot.value());
        if (latest.size() != 1
                || number(latest.getFirst(), "version") != proposal.candidatePotVersion())
            throw bad("Candidate metadata missing");
        if (!Boolean.TRUE.equals(latest.getFirst().get("owned")))
            throw bad("Candidate metadata belongs to another physical transaction");
    }

    @Override
    public void createShareholders(
            PotId pot, TransitionResult.Changed<List<ShareholderState>> proposal) {
        SpringAttemptTransactions.revision(
                rows.dataSource,
                () -> {
                    proposal(pot, proposal);
                    if (proposal.changes().isEmpty()) throw bad("Empty creation");
                    var ids = new HashSet<ShareholderId>();
                    for (var state : proposal.changes()) {
                        samePot(pot, state.header().value().potId());
                        if (!ids.add(state.header().value().shareholderId())
                                || state.header().value().deleted())
                            throw bad("Invalid shareholder creation");
                        initial(state.header(), proposal.candidatePotVersion());
                        initial(state.details(), proposal.candidatePotVersion());
                        initial(state.weight(), proposal.candidatePotVersion());
                    }
                    for (var state : proposal.changes()) {
                        rows.jdbc.update(
                                "insert into v3_shareholder_identity(pot_id,object_id,created_at)"
                                        + " values (?,?,?)",
                                pot.value(),
                                state.header().value().shareholderId().value(),
                                proposal.candidatePotVersion());
                        insertShareholderHeader(state.header());
                        insertShareholderDetails(state.details());
                        insertShareholderWeight(state.weight());
                    }
                });
    }

    @Override
    public void createExpense(PotId pot, TransitionResult.Changed<ExpenseState> proposal) {
        SpringAttemptTransactions.revision(
                rows.dataSource,
                () -> {
                    proposal(pot, proposal);
                    var state = proposal.changes();
                    samePot(pot, state.header().value().potId());
                    if (state.header().value().deleted()) throw bad("Invalid Expense creation");
                    initial(state.header(), proposal.candidatePotVersion());
                    initial(state.details(), proposal.candidatePotVersion());
                    initial(state.financials(), proposal.candidatePotVersion());
                    financialReferences(pot, state.financials().value(), proposal);
                    rows.jdbc.update(
                            "insert into v3_expense_identity(pot_id,object_id,created_at) values"
                                    + " (?,?,?)",
                            pot.value(),
                            state.header().value().expenseId().value(),
                            proposal.candidatePotVersion());
                    insertExpenseHeader(state.header());
                    insertExpenseDetails(state.details());
                    insertExpenseFinancials(state.financials());
                });
    }

    @Override
    public void revisePotDetails(
            PotId pot, TransitionResult.Changed<Versioned<PotDetails>> proposal) {
        SpringAttemptTransactions.revision(
                rows.dataSource,
                () -> {
                    proposal(pot, proposal);
                    var next = proposal.changes();
                    samePot(pot, next.value().potId());
                    var target = new RevisionTarget("pot_details", pot, pot.value(), next);
                    var old = check(target, proposal);
                    if (!uuid(old, "creator_id").equals(next.value().creatorId().value())) {
                        throw bad("Creator must remain immutable");
                    }
                    close(target, old);
                    insertPotDetails(next);
                });
    }

    private record HeaderDescriptor<T>(
            String table,
            java.util.function.Function<Versioned<T>, PotId> potOf,
            java.util.function.Function<Versioned<T>, UUID> idOf,
            java.util.function.Predicate<Versioned<T>> isDeleted,
            java.util.function.Consumer<Versioned<T>> inserter) {}

    private record BatchDescriptor<T>(
            String table,
            java.util.function.Function<T, PotId> potOf,
            java.util.function.Function<T, ShareholderId> idOf,
            String mixedKeyMessage,
            java.util.function.Consumer<Versioned<T>> inserter) {}

    @Override
    public void revisePotHeader(
            PotId pot, TransitionResult.Changed<Versioned<PotHeader>> proposal) {
        reviseDeletedHeader(
                pot,
                proposal,
                new HeaderDescriptor<>(
                        "pot_header",
                        next -> next.value().potId(),
                        next -> next.value().potId().value(),
                        next -> next.value().deleted(),
                        this::insertPotHeader));
    }

    @Override
    public void reviseShareholderDetails(
            PotId pot,
            TransitionResult.Changed<Map<ShareholderId, Versioned<ShareholderDetails>>> proposal) {
        reviseShareholderBatch(
                pot,
                proposal,
                new BatchDescriptor<>(
                        "shareholder_details",
                        ShareholderDetails::potId,
                        ShareholderDetails::shareholderId,
                        "Mixed details key",
                        this::insertShareholderDetails));
    }

    @Override
    public void reviseShareholderWeights(
            PotId pot,
            TransitionResult.Changed<Map<ShareholderId, Versioned<ShareholderWeight>>> proposal) {
        reviseShareholderBatch(
                pot,
                proposal,
                new BatchDescriptor<>(
                        "shareholder_weight",
                        ShareholderWeight::potId,
                        ShareholderWeight::shareholderId,
                        "Mixed weight key",
                        this::insertShareholderWeight));
    }

    @Override
    public void reviseShareholderHeader(
            PotId pot, TransitionResult.Changed<Versioned<ShareholderHeader>> proposal) {
        reviseDeletedHeader(
                pot,
                proposal,
                new HeaderDescriptor<>(
                        "shareholder_header",
                        next -> next.value().potId(),
                        next -> next.value().shareholderId().value(),
                        next -> next.value().deleted(),
                        this::insertShareholderHeader));
    }

    @Override
    public void reviseExpenseDetails(
            PotId pot, TransitionResult.Changed<Versioned<ExpenseDetails>> proposal) {
        SpringAttemptTransactions.revision(
                rows.dataSource,
                () -> {
                    proposal(pot, proposal);
                    var next = proposal.changes();
                    samePot(pot, next.value().potId());
                    var target =
                            new RevisionTarget(
                                    "expense_details", pot, next.value().expenseId().value(), next);
                    var old = check(target, proposal);
                    close(target, old);
                    insertExpenseDetails(next);
                });
    }

    @Override
    public void reviseExpenseFinancials(
            PotId pot, TransitionResult.Changed<Versioned<ExpenseFinancials>> proposal) {
        SpringAttemptTransactions.revision(
                rows.dataSource,
                () -> {
                    proposal(pot, proposal);
                    var next = proposal.changes();
                    samePot(pot, next.value().potId());
                    var target =
                            new RevisionTarget(
                                    "expense_financials",
                                    pot,
                                    next.value().expenseId().value(),
                                    next);
                    var old = check(target, proposal);
                    financialReferences(pot, next.value(), proposal);
                    close(target, old);
                    insertExpenseFinancials(next);
                });
    }

    @Override
    public void reviseExpenseHeader(
            PotId pot, TransitionResult.Changed<Versioned<ExpenseHeader>> proposal) {
        reviseDeletedHeader(
                pot,
                proposal,
                new HeaderDescriptor<>(
                        "expense_header",
                        next -> next.value().potId(),
                        next -> next.value().expenseId().value(),
                        next -> next.value().deleted(),
                        this::insertExpenseHeader));
    }

    private <T> void reviseDeletedHeader(
            PotId pot,
            TransitionResult.Changed<Versioned<T>> proposal,
            HeaderDescriptor<T> desc) {
        SpringAttemptTransactions.revision(
                rows.dataSource,
                () -> {
                    proposal(pot, proposal);
                    var next = proposal.changes();
                    samePot(pot, desc.potOf().apply(next));
                    var target =
                            new RevisionTarget(desc.table(), pot, desc.idOf().apply(next), next);
                    var old = deletion(target, desc.isDeleted().test(next), proposal);
                    close(target, old);
                    desc.inserter().accept(next);
                });
    }

    private <T> void reviseShareholderBatch(
            PotId pot,
            TransitionResult.Changed<Map<ShareholderId, Versioned<T>>> proposal,
            BatchDescriptor<T> desc) {
        SpringAttemptTransactions.revision(
                rows.dataSource,
                () -> {
                    proposal(pot, proposal);
                    var targets = new LinkedHashMap<UUID, Versioned<?>>();
                    for (var entry : proposal.changes().entrySet()) {
                        var next = entry.getValue();
                        samePot(pot, desc.potOf().apply(next.value()));
                        if (!entry.getKey().equals(desc.idOf().apply(next.value()))) {
                            throw bad(desc.mixedKeyMessage());
                        }
                        targets.put(entry.getKey().value(), next);
                    }
                    var old = checkAll(desc.table(), pot, targets, proposal);
                    for (var entry : proposal.changes().entrySet()) {
                        var id = entry.getKey().value();
                        close(
                                new RevisionTarget(desc.table(), pot, id, entry.getValue()),
                                old.get(id));
                        desc.inserter().accept(entry.getValue());
                    }
                });
    }

    private Map<String, Object> check(
            RevisionTarget target, TransitionResult.Changed<?> proposal) {
        return checkAll(target.table(), target.pot(), Map.of(target.id(), target.revision()), proposal)
                .get(target.id());
    }

    private Map<UUID, Map<String, Object>> checkAll(
            String table,
            PotId pot,
            Map<UUID, Versioned<?>> next,
            TransitionResult.Changed<?> proposal) {
        if (next.isEmpty()) throw bad("Empty revision batch");
        var births = resolveBirths(table, pot, next.keySet());
        if (!births.keySet().equals(next.keySet())) throw bad("Missing revision identity");
        var old = rows.openComponent(table, pot, proposal.expectedPotVersion(), births);
        verifyNextVersions(old, next, proposal.candidatePotVersion());
        return old;
    }

    private Map<UUID, Long> resolveBirths(String table, PotId pot, Set<UUID> ids) {
        if (table.startsWith("pot_")) return Map.of(pot.value(), 1L);
        String kind = table.startsWith("shareholder_") ? "shareholder" : "expense";
        return rows.identities(kind, pot, ids);
    }

    private static void verifyNextVersions(
            Map<UUID, Map<String, Object>> old,
            Map<UUID, Versioned<?>> next,
            long candidatePotVersion) {
        next.forEach(
                (id, revision) -> {
                    if (revision.activeFrom() != candidatePotVersion
                            || Math.incrementExact(number(old.get(id), "local_version"))
                                    != revision.localVersion()) {
                        throw bad("Unexpected local revision or applicability start");
                    }
                });
    }

    private Map<String, Object> deletion(
            RevisionTarget target, boolean deleted, TransitionResult.Changed<?> proposal) {
        var old = check(target, proposal);
        if (!deleted || (boolean) old.get("deleted")) {
            throw bad("Header deletion must be effective");
        }
        return old;
    }

    private void close(RevisionTarget target, Map<String, Object> old) {
        int count =
                rows.jdbc.update(
                        "update v3_"
                                + target.table()
                                + " set active_to=? where pot_id=? and object_id=? and"
                                + " revision_id=? and local_version=? and active_from=? and"
                                + " active_to is null and active_from<?",
                        target.revision().activeFrom(),
                        target.pot().value(),
                        target.id(),
                        number(old, "revision_id"),
                        Math.decrementExact(target.revision().localVersion()),
                        number(old, "active_from"),
                        target.revision().activeFrom());
        if (count != 1) throw bad("Revision close conflict");
    }

    private void financialReferences(
            PotId pot, ExpenseFinancials financials, TransitionResult.Changed<?> proposal) {
        var ids = new HashSet<UUID>();
        ids.add(financials.payerId().value());
        financials.participants().keySet().forEach(id -> ids.add(id.value()));
        var births = rows.identities("shareholder", pot, ids);
        if (!births.keySet().equals(ids)) throw bad("Invalid financial reference identity");
        var headers =
                rows.openComponent(
                        "shareholder_header", pot, proposal.expectedPotVersion(), births);
        if (headers.values().stream().anyMatch(row -> (boolean) row.get("deleted")))
            throw bad("Invalid financial reference");
    }

    private void initial(Versioned<?> revision, long version) {
        if (revision.localVersion() != 1 || revision.activeFrom() != version)
            throw bad("Invalid initial revision");
    }

    private void samePot(PotId expected, PotId actual) {
        if (!expected.equals(actual)) throw bad("Mixed Pot proposal");
    }

    private void insert(RevisionTarget target, RevisionColumns cols) {
        var args =
                new ArrayList<Object>(
                        List.of(
                                target.pot().value(),
                                target.id(),
                                target.revision().localVersion(),
                                target.revision().activeFrom()));
        args.addAll(Arrays.asList(cols.values()));
        String placeholders = String.join(",", Collections.nCopies(args.size(), "?"));
        rows.jdbc.update(
                "insert into v3_"
                        + target.table()
                        + "(pot_id,object_id,local_version,active_from,"
                        + cols.columns()
                        + ") values ("
                        + placeholders
                        + ")",
                args.toArray());
    }

    private void insertPotHeader(Versioned<PotHeader> revision) {
        var v = revision.value();
        insert(
                new RevisionTarget("pot_header", v.potId(), v.potId().value(), revision),
                new RevisionColumns("deleted", v.deleted()));
    }

    private void insertPotDetails(Versioned<PotDetails> revision) {
        var v = revision.value();
        insert(
                new RevisionTarget("pot_details", v.potId(), v.potId().value(), revision),
                new RevisionColumns("label,creator_id", v.label().value(), v.creatorId().value()));
    }

    private void insertShareholderHeader(Versioned<ShareholderHeader> revision) {
        var v = revision.value();
        insert(
                new RevisionTarget(
                        "shareholder_header", v.potId(), v.shareholderId().value(), revision),
                new RevisionColumns("deleted", v.deleted()));
    }

    private void insertShareholderDetails(Versioned<ShareholderDetails> revision) {
        var v = revision.value();
        insert(
                new RevisionTarget(
                        "shareholder_details", v.potId(), v.shareholderId().value(), revision),
                new RevisionColumns(
                        "name,user_id",
                        v.name().value(),
                        v.userId().map(id -> id.value()).orElse(null)));
    }

    private void insertShareholderWeight(Versioned<ShareholderWeight> revision) {
        var v = revision.value();
        insert(
                new RevisionTarget(
                        "shareholder_weight", v.potId(), v.shareholderId().value(), revision),
                new RevisionColumns(
                        "numerator,denominator",
                        v.defaultWeight().value().numerator().toString(),
                        v.defaultWeight().value().denominator().toString()));
    }

    private void insertExpenseHeader(Versioned<ExpenseHeader> revision) {
        var v = revision.value();
        insert(
                new RevisionTarget("expense_header", v.potId(), v.expenseId().value(), revision),
                new RevisionColumns("deleted", v.deleted()));
    }

    private void insertExpenseDetails(Versioned<ExpenseDetails> revision) {
        var v = revision.value();
        insert(
                new RevisionTarget("expense_details", v.potId(), v.expenseId().value(), revision),
                new RevisionColumns(
                        "label,expense_date",
                        v.label().value(),
                        java.sql.Date.valueOf(v.date())));
    }

    private void insertExpenseFinancials(Versioned<ExpenseFinancials> revision) {
        var v = revision.value();
        insert(
                new RevisionTarget(
                        "expense_financials", v.potId(), v.expenseId().value(), revision),
                new RevisionColumns(
                        "numerator,denominator,payer_id",
                        v.amount().value().numerator().toString(),
                        v.amount().value().denominator().toString(),
                        v.payerId().value()));
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
