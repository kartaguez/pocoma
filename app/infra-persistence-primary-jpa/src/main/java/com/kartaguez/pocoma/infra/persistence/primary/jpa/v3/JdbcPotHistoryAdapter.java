package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import static com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.PotHistoryRows.*;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.v3.ComponentRead;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.v3.PotHistoryPort;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

import javax.sql.DataSource;

/** Explicitly composed V3 WRITE reader. Never auto-activates alongside the V2 runtime. */
public final class JdbcPotHistoryAdapter implements PotHistoryPort {
    private final PotHistoryRows rows;

    public JdbcPotHistoryAdapter(DataSource dataSource) {
        rows = new PotHistoryRows(dataSource);
    }

    @Override
    public Optional<Snapshot> atVersion(PotId pot, long version) {
        rows.requireTransaction(true);
        if (version < 1) throw new IllegalArgumentException("Positive version required");
        var versions = rows.versions(pot);
        if (versions.isEmpty()) return Optional.empty();
        long latest = number(versions.getLast(), "version");
        if (version > latest) throw bad("Requested Pot version does not exist");
        var potBirth = Map.of(pot.value(), 1L);
        var header =
                potHeader(
                        rows.component("pot_header", pot, version, latest, potBirth)
                                .get(pot.value()));
        var details =
                potDetails(
                        rows.component("pot_details", pot, version, latest, potBirth)
                                .get(pot.value()));
        var shareholderBirths = rows.identities("shareholder", pot, null);
        var shareholders = shareholders(pot, version, latest, shareholderBirths);
        var expenses = expenses(pot, version, latest, rows.identities("expense", pot, null));
        validateReferences(shareholders, expenses, shareholderBirths);
        return Optional.of(
                new Snapshot(
                        pot,
                        version,
                        instant(versions.get(Math.toIntExact(version - 1)), "recorded_at"),
                        new PotState(header, details),
                        shareholders,
                        expenses));
    }

    private Map<ShareholderId, ShareholderState> shareholders(
            PotId pot, long version, long latest, Map<UUID, Long> births) {
        var headers = rows.component("shareholder_header", pot, version, latest, births);
        var details = rows.component("shareholder_details", pot, version, latest, births);
        var weights = rows.component("shareholder_weight", pot, version, latest, births);
        var result = new LinkedHashMap<ShareholderId, ShareholderState>();
        for (var id : headers.keySet())
            result.put(
                    new ShareholderId(id),
                    new ShareholderState(
                            shareholderHeader(headers.get(id)),
                            shareholderDetails(details.get(id)),
                            shareholderWeight(weights.get(id))));
        return Map.copyOf(result);
    }

    private Map<ExpenseId, ExpenseState> expenses(
            PotId pot, long version, long latest, Map<UUID, Long> births) {
        var headers = rows.component("expense_header", pot, version, latest, births);
        var details = rows.component("expense_details", pot, version, latest, births);
        var financialRows = rows.component("expense_financials", pot, version, latest, births);
        var financials = financials(financialRows);
        var result = new LinkedHashMap<ExpenseId, ExpenseState>();
        for (var id : headers.keySet())
            result.put(
                    new ExpenseId(id),
                    new ExpenseState(
                            expenseHeader(headers.get(id)),
                            expenseDetails(details.get(id)),
                            financials.get(id)));
        return Map.copyOf(result);
    }

    @Override
    public Optional<SelectedPot> current(PotId pot, UserId actor, Selection selection) {
        rows.requireTransaction(true);
        Objects.requireNonNull(actor);
        Objects.requireNonNull(selection);
        var versions = rows.versions(pot);
        if (versions.isEmpty()) return Optional.empty();
        long version = number(versions.getLast(), "version");
        return Optional.of(selected(pot, actor, selection, version, version));
    }

    @Override
    public Optional<SelectedPot> componentsAtVersion(
            PotId pot, long version, UserId actor, Selection selection) {
        rows.requireTransaction(true);
        Objects.requireNonNull(actor);
        Objects.requireNonNull(selection);
        if (version < 1) throw new IllegalArgumentException("Positive version required");
        var versions = rows.versions(pot);
        if (versions.isEmpty()) return Optional.empty();
        long latest = number(versions.getLast(), "version");
        if (version > latest) throw bad("Requested version absent");
        return Optional.of(selected(pot, actor, selection, version, latest));
    }

    private SelectedPot selected(
            PotId pot, UserId actor, Selection selection, long version, long latest) {
        var potBirth = Map.of(pot.value(), 1L);
        var header =
                potHeader(
                        rows.component("pot_header", pot, version, latest, potBirth)
                                .get(pot.value()));
        var detailRow =
                rows.component(
                                "pot_details",
                                pot,
                                version,
                                latest,
                                potBirth,
                                selection.potDetails() ? "*" : "creator_id")
                        .get(pot.value());
        // Authorization needs active attachment facts; weights and Expense payloads are not read
        // here.
        var allBirths = rows.identities("shareholder", pot, null);
        var allHeaders = rows.component("shareholder_header", pot, version, latest, allBirths);
        var allDetails =
                rows.component("shareholder_details", pot, version, latest, allBirths, "user_id");
        boolean attached =
                allHeaders.entrySet().stream()
                        .anyMatch(
                                entry ->
                                        !(boolean) entry.getValue().get("deleted")
                                                && actor.value()
                                                        .equals(
                                                                allDetails
                                                                        .get(entry.getKey())
                                                                        .get("user_id")));
        var auth =
                new AuthorizationFacts(
                        pot, version, new UserId(uuid(detailRow, "creator_id")), actor, attached);
        var selectedShareholders =
                selectedShareholders(pot, version, latest, selection, allBirths, allHeaders);
        var selectedExpenses = selectedExpenses(pot, version, latest, selection);
        validateSelectedReferences(selectedExpenses, allBirths, allHeaders);
        ComponentRead<PotDetails> selectedDetails =
                selection.potDetails()
                        ? new ComponentRead.Present<>(potDetails(detailRow))
                        : new ComponentRead.NotLoaded<>();
        return new SelectedPot(
                new PotFacts(pot, version, header, auth),
                selectedDetails,
                selectedShareholders,
                selectedExpenses);
    }

    private Map<ShareholderId, SelectedShareholder> selectedShareholders(
            PotId pot,
            long version,
            long latest,
            Selection selection,
            Map<UUID, Long> births,
            Map<UUID, Map<String, Object>> headers) {
        var selectedBirths = new LinkedHashMap<UUID, Long>();
        for (var id : selection.shareholders())
            if (births.containsKey(id.value()))
                selectedBirths.put(id.value(), births.get(id.value()));
        Map<UUID, Map<String, Object>> details =
                selection.shareholderDetails()
                        ? rows.component(
                                "shareholder_details", pot, version, latest, selectedBirths)
                        : Map.of();
        var weights =
                selection.shareholderWeights()
                        ? rows.component("shareholder_weight", pot, version, latest, selectedBirths)
                        : Map.<UUID, Map<String, Object>>of();
        var result = new LinkedHashMap<ShareholderId, SelectedShareholder>();
        for (var id : selection.shareholders()) {
            var raw = headers.get(id.value());
            var reference =
                    new ShareholderReference(
                            pot,
                            version,
                            id,
                            raw == null ? 0L : births.get(id.value()),
                            Optional.ofNullable(raw).map(this::shareholderHeader));
            result.put(
                    id,
                    new SelectedShareholder(
                            reference,
                            read(
                                    selection.shareholderDetails(),
                                    details.get(id.value()),
                                    this::shareholderDetails),
                            read(
                                    selection.shareholderWeights(),
                                    weights.get(id.value()),
                                    this::shareholderWeight)));
        }
        return Map.copyOf(result);
    }

    private Map<ExpenseId, SelectedExpense> selectedExpenses(
            PotId pot, long version, long latest, Selection selection) {
        var ids =
                selection.expenses().stream()
                        .map(ExpenseId::value)
                        .collect(java.util.stream.Collectors.toSet());
        var births = rows.identities("expense", pot, ids);
        var headers = rows.component("expense_header", pot, version, latest, births);
        var details =
                selection.expenseDetails()
                        ? rows.component("expense_details", pot, version, latest, births)
                        : Map.<UUID, Map<String, Object>>of();
        var financialRows =
                selection.expenseFinancials()
                        ? rows.component("expense_financials", pot, version, latest, births)
                        : Map.<UUID, Map<String, Object>>of();
        var financials = financials(financialRows);
        var result = new LinkedHashMap<ExpenseId, SelectedExpense>();
        for (var id : selection.expenses()) {
            ComponentRead<ExpenseFinancials> f =
                    !selection.expenseFinancials()
                            ? new ComponentRead.NotLoaded<>()
                            : financials.containsKey(id.value())
                                    ? new ComponentRead.Present<>(financials.get(id.value()))
                                    : new ComponentRead.Absent<>();
            result.put(
                    id,
                    new SelectedExpense(
                            read(true, headers.get(id.value()), this::expenseHeader),
                            read(
                                    selection.expenseDetails(),
                                    details.get(id.value()),
                                    this::expenseDetails),
                            f));
        }
        return Map.copyOf(result);
    }

    @Override
    public ActiveShareholderUsage activeUsage(PotId pot, long version, ShareholderId shareholder) {
        rows.requireTransaction(true);
        var versions = rows.versions(pot);
        if (versions.isEmpty() || version < 1 || version > number(versions.getLast(), "version"))
            throw bad("Unknown usage version");
        long latest = number(versions.getLast(), "version");
        var shareholders = rows.identities("shareholder", pot, null);
        if (!shareholders.containsKey(shareholder.value())
                || shareholders.get(shareholder.value()) > version)
            throw bad("Unknown usage subject");
        var shareholderHeaders =
                rows.component("shareholder_header", pot, version, latest, shareholders);
        var births = rows.identities("expense", pot, null);
        var headers = rows.component("expense_header", pot, version, latest, births);
        var financials =
                financials(rows.component("expense_financials", pot, version, latest, births));
        for (var entry : financials.entrySet()) {
            var financial = entry.getValue();
            var ids = new HashSet<>(financial.value().participants().keySet());
            ids.add(financial.value().payerId());
            for (var id : ids) {
                var reference = shareholderHeaders.get(id.value());
                if (reference == null
                        || shareholders.get(id.value()) > financial.activeFrom()
                        || (!(boolean) headers.get(entry.getKey()).get("deleted")
                                && (boolean) reference.get("deleted")))
                    throw bad("Inconsistent Expense in exhaustive usage scope");
            }
        }
        boolean referenced =
                headers.entrySet().stream()
                        .anyMatch(
                                entry -> {
                                    if ((boolean) entry.getValue().get("deleted")) return false;
                                    var f = financials.get(entry.getKey()).value();
                                    return f.payerId().equals(shareholder)
                                            || f.participants().containsKey(shareholder);
                                });
        return new ActiveShareholderUsage(pot, version, shareholder, referenced, true);
    }

    private Map<UUID, Versioned<ExpenseFinancials>> financials(
            Map<UUID, Map<String, Object>> financialRows) {
        if (financialRows.isEmpty()) return Map.of();
        var revisionIds =
                financialRows.values().stream().map(row -> number(row, "revision_id")).toList();
        var participants = new HashMap<Long, Map<ShareholderId, Optional<Weight>>>();
        var owners = new HashMap<Long, UUID>();
        financialRows
                .values()
                .forEach(row -> owners.put(number(row, "revision_id"), uuid(row, "pot_id")));
        for (var row :
                rows.named.queryForList(
                        "select * from v3_expense_participant where financial_revision_id in"
                                + " (:ids)",
                        Map.of("ids", revisionIds))) {
            if (!Objects.equals(
                    owners.get(number(row, "financial_revision_id")), uuid(row, "pot_id")))
                throw bad("Mixed participant owner");
            var map =
                    participants.computeIfAbsent(
                            number(row, "financial_revision_id"), key -> new LinkedHashMap<>());
            Optional<Weight> weight =
                    row.get("numerator") == null && row.get("denominator") == null
                            ? Optional.empty()
                            : Optional.of(new Weight(rational(row)));
            if (map.put(new ShareholderId(uuid(row, "shareholder_id")), weight) != null)
                throw bad("Duplicate persisted participant");
        }
        var result = new LinkedHashMap<UUID, Versioned<ExpenseFinancials>>();
        for (var entry : financialRows.entrySet()) {
            var row = entry.getValue();
            var shares = participants.get(number(row, "revision_id"));
            if (shares == null || shares.isEmpty()) throw bad("Missing persisted participants");
            result.put(
                    entry.getKey(),
                    versioned(
                            row,
                            new ExpenseFinancials(
                                    new PotId(uuid(row, "pot_id")),
                                    new ExpenseId(entry.getKey()),
                                    new Amount(rational(row)),
                                    new ShareholderId(uuid(row, "payer_id")),
                                    shares)));
        }
        return Map.copyOf(result);
    }

    private void validateReferences(
            Map<ShareholderId, ShareholderState> shareholders,
            Map<ExpenseId, ExpenseState> expenses,
            Map<UUID, Long> births) {
        for (var expense : expenses.values()) {
            var f = expense.financials();
            var ids = new HashSet<>(f.value().participants().keySet());
            ids.add(f.value().payerId());
            for (var id : ids) {
                var shareholder = shareholders.get(id);
                if (shareholder == null
                        || births.get(id.value()) > f.activeFrom()
                        || (!expense.header().value().deleted()
                                && shareholder.header().value().deleted()))
                    throw bad("Expense references an unavailable Shareholder");
            }
        }
    }

    private void validateSelectedReferences(
            Map<ExpenseId, SelectedExpense> expenses,
            Map<UUID, Long> births,
            Map<UUID, Map<String, Object>> shareholderHeaders) {
        for (var expense : expenses.values())
            if (expense.financials() instanceof ComponentRead.Present<ExpenseFinancials> present) {
                var financials = present.revision();
                var header = ((ComponentRead.Present<ExpenseHeader>) expense.header()).revision();
                var ids = new HashSet<>(financials.value().participants().keySet());
                ids.add(financials.value().payerId());
                for (var id : ids) {
                    var shareholder = shareholderHeaders.get(id.value());
                    if (shareholder == null
                            || births.get(id.value()) > financials.activeFrom()
                            || (!header.value().deleted() && (boolean) shareholder.get("deleted")))
                        throw bad("Invalid selective financial reference");
                }
            }
    }

    private <T> ComponentRead<T> read(
            boolean loaded,
            Map<String, Object> row,
            Function<Map<String, Object>, Versioned<T>> convert) {
        return !loaded
                ? new ComponentRead.NotLoaded<>()
                : row == null
                        ? new ComponentRead.Absent<>()
                        : new ComponentRead.Present<>(convert.apply(row));
    }

    private <T> Versioned<T> versioned(Map<String, Object> row, T value) {
        return new Versioned<>(number(row, "local_version"), number(row, "active_from"), value);
    }

    private Versioned<PotHeader> potHeader(Map<String, Object> row) {
        return versioned(
                row, new PotHeader(new PotId(uuid(row, "pot_id")), (boolean) row.get("deleted")));
    }

    private Versioned<PotDetails> potDetails(Map<String, Object> row) {
        return versioned(
                row,
                new PotDetails(
                        new PotId(uuid(row, "pot_id")),
                        new Label((String) row.get("label")),
                        new UserId(uuid(row, "creator_id"))));
    }

    private Versioned<ShareholderHeader> shareholderHeader(Map<String, Object> row) {
        return versioned(
                row,
                new ShareholderHeader(
                        new PotId(uuid(row, "pot_id")),
                        new ShareholderId(uuid(row, "object_id")),
                        (boolean) row.get("deleted")));
    }

    private Versioned<ShareholderDetails> shareholderDetails(Map<String, Object> row) {
        return versioned(
                row,
                new ShareholderDetails(
                        new PotId(uuid(row, "pot_id")),
                        new ShareholderId(uuid(row, "object_id")),
                        new Name((String) row.get("name")),
                        Optional.ofNullable(uuid(row, "user_id")).map(UserId::new)));
    }

    private Versioned<ShareholderWeight> shareholderWeight(Map<String, Object> row) {
        return versioned(
                row,
                new ShareholderWeight(
                        new PotId(uuid(row, "pot_id")),
                        new ShareholderId(uuid(row, "object_id")),
                        new Weight(rational(row))));
    }

    private Versioned<ExpenseHeader> expenseHeader(Map<String, Object> row) {
        return versioned(
                row,
                new ExpenseHeader(
                        new PotId(uuid(row, "pot_id")),
                        new ExpenseId(uuid(row, "object_id")),
                        (boolean) row.get("deleted")));
    }

    private Versioned<ExpenseDetails> expenseDetails(Map<String, Object> row) {
        return versioned(
                row,
                new ExpenseDetails(
                        new PotId(uuid(row, "pot_id")),
                        new ExpenseId(uuid(row, "object_id")),
                        new Label((String) row.get("label")),
                        ((java.sql.Date) row.get("expense_date")).toLocalDate()));
    }

    static Fraction rational(Map<String, Object> row) {
        try {
            String numerator = (String) row.get("numerator"),
                    denominator = (String) row.get("denominator");
            var fraction = new Fraction(new BigInteger(numerator), new BigInteger(denominator));
            if (!fraction.numerator().toString().equals(numerator)
                    || !fraction.denominator().toString().equals(denominator))
                throw bad("Noncanonical persisted rational");
            return fraction;
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw bad("Invalid persisted rational");
        }
    }
}
