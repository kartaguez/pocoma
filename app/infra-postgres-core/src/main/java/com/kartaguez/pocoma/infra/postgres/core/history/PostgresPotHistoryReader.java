package com.kartaguez.pocoma.infra.postgres.core.history;

import static com.kartaguez.pocoma.infra.postgres.core.history.PotHistoryRows.*;

import com.kartaguez.pocoma.domain.pot.*;
import com.kartaguez.pocoma.domain.pot.expense.*;
import com.kartaguez.pocoma.domain.pot.invitation.*;
import com.kartaguez.pocoma.domain.pot.revision.*;
import com.kartaguez.pocoma.domain.pot.shareholder.*;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.domain.pot.PotHistory.*;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;

import org.springframework.jdbc.support.SqlArrayValue;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

import javax.sql.DataSource;

/** Explicitly composed V3 WRITE reader. Never auto-activates alongside the V2 runtime. */
public class PostgresPotHistoryReader {
    private record VersionScope(PotId pot, long version, long latest) {}

    private record ShareholderIndex(
            Map<UUID, Long> births, Map<UUID, Map<String, Object>> headers) {}

    private final PotHistoryRows rows;

    public PostgresPotHistoryReader(DataSource dataSource) {
        rows = new PotHistoryRows(dataSource);
    }

    /** Maintenance check in a caller-owned snapshot; deliberately outside point reads. */
    public void verifyHistory(PotId pot) {
        SpringAttemptTransactions.participating(
                rows.dataSource,
                () -> {
                    verifyHistoryInSnapshot(pot);
                    return null;
                });
    }

    private void verifyHistoryInSnapshot(PotId pot) {
        rows.requireTransaction(true);
        var versions = rows.versions(pot);
        if (versions.isEmpty()) return;
        long latest = number(versions.getLast(), "version");
        var potBirth = Map.of(pot.value(), 1L);
        rows.checkedComponent("pot_header", pot, latest, latest, potBirth);
        rows.checkedComponent("pot_details", pot, latest, latest, potBirth);
        var shareholders = rows.identities("shareholder", pot, null);
        for (var table : List.of("shareholder_header", "shareholder_details", "shareholder_weight"))
            rows.checkedComponent(table, pot, latest, latest, shareholders);
        var expenses = rows.identities("expense", pot, null);
        for (var table : List.of("expense_header", "expense_details", "expense_financials"))
            rows.checkedComponent(table, pot, latest, latest, expenses);
    }

    public Optional<Snapshot> atVersion(PotId pot, long version) {
        return SpringAttemptTransactions.participating(
                rows.dataSource,
                () -> {
                    rows.requireTransaction(true);
                    if (version < 1)
                        throw new IllegalArgumentException("Positive version required");
                    var metadata = rows.version(pot, version);
                    if (metadata == null) return Optional.empty();
                    long latest = number(metadata, "latest");
                    var potBirth = Map.of(pot.value(), 1L);
                    var scope = new VersionScope(pot, version, latest);
                    var header =
                            potHeader(
                                    rows.component("pot_header", pot, version, latest, potBirth)
                                            .get(pot.value()));
                    var details =
                            potDetails(
                                    rows.component("pot_details", pot, version, latest, potBirth)
                                            .get(pot.value()));
                    var shareholderBirths = rows.identities("shareholder", pot, null, version);
                    var shareholders = shareholders(scope, shareholderBirths);
                    var expenses =
                            expenses(scope, rows.identities("expense", pot, null, version));
                    validateReferences(shareholders, expenses, shareholderBirths);
                    return Optional.of(
                            new Snapshot(
                                    pot,
                                    version,
                                    instant(metadata, "recorded_at"),
                                    new PotState(header, details),
                                    shareholders,
                                    expenses));
                });
    }

    public Optional<PotFinancialState> financialAtVersion(PotId pot, long version) {
        return SpringAttemptTransactions.participating(
                rows.dataSource,
                () -> {
                    rows.requireTransaction(true);
                    if (version < 1)
                        throw new IllegalArgumentException("Positive version required");
                    var metadata = rows.version(pot, version);
                    if (metadata == null) return Optional.empty();
                    long latest = number(metadata, "latest");
                    var header =
                            potHeader(
                                    rows.component(
                                                    "pot_header",
                                                    pot,
                                                    version,
                                                    latest,
                                                    Map.of(pot.value(), 1L))
                                            .get(pot.value()));
                    var births = rows.identities("shareholder", pot, null, version);
                    var headers =
                            rows.component("shareholder_header", pot, version, latest, births);
                    var weights =
                            rows.component("shareholder_weight", pot, version, latest, births);
                    var shareholders =
                            new LinkedHashMap<ShareholderId, PotFinancialState.Shareholder>();
                    headers.forEach(
                            (id, row) ->
                                    shareholders.put(
                                            new ShareholderId(id),
                                            new PotFinancialState.Shareholder(
                                                    shareholderHeader(row),
                                                    shareholderWeight(weights.get(id)))));
                    var expenseBirths = rows.identities("expense", pot, null, version);
                    var expenseHeaders =
                            rows.component("expense_header", pot, version, latest, expenseBirths);
                    var financials =
                            financials(
                                    rows.component(
                                            "expense_financials",
                                            pot,
                                            version,
                                            latest,
                                            expenseBirths));
                    var expenses = new LinkedHashMap<ExpenseId, PotFinancialState.Expense>();
                    financials.forEach(
                            (id, value) -> {
                                var references =
                                        new HashSet<>(value.value().participants().keySet());
                                references.add(value.value().payerId());
                                for (var reference : references)
                                    if (!births.containsKey(reference.value())
                                            || births.get(reference.value()) > value.activeFrom())
                                        throw bad("Financial reference created after Financials");
                                expenses.put(
                                        new ExpenseId(id),
                                        new PotFinancialState.Expense(
                                                expenseHeader(expenseHeaders.get(id)), value));
                            });
                    return Optional.of(
                            new PotFinancialState(pot, version, header, shareholders, expenses));
                });
    }

    private Map<ShareholderId, ShareholderState> shareholders(
            VersionScope scope, Map<UUID, Long> births) {
        var headers =
                rows.component(
                        "shareholder_header", scope.pot(), scope.version(), scope.latest(), births);
        var details =
                rows.component(
                        "shareholder_details", scope.pot(), scope.version(), scope.latest(), births);
        var weights =
                rows.component(
                        "shareholder_weight", scope.pot(), scope.version(), scope.latest(), births);
        var result = new LinkedHashMap<ShareholderId, ShareholderState>();
        for (var id : headers.keySet()) {
            result.put(
                    new ShareholderId(id),
                    new ShareholderState(
                            shareholderHeader(headers.get(id)),
                            shareholderDetails(details.get(id)),
                            shareholderWeight(weights.get(id))));
        }
        return Map.copyOf(result);
    }

    private Map<ExpenseId, ExpenseState> expenses(VersionScope scope, Map<UUID, Long> births) {
        var headers =
                rows.component(
                        "expense_header", scope.pot(), scope.version(), scope.latest(), births);
        var details =
                rows.component(
                        "expense_details", scope.pot(), scope.version(), scope.latest(), births);
        var financialRows =
                rows.component(
                        "expense_financials", scope.pot(), scope.version(), scope.latest(), births);
        var financials = financials(financialRows);
        var result = new LinkedHashMap<ExpenseId, ExpenseState>();
        for (var id : headers.keySet()) {
            result.put(
                    new ExpenseId(id),
                    new ExpenseState(
                            expenseHeader(headers.get(id)),
                            expenseDetails(details.get(id)),
                            financials.get(id)));
        }
        return Map.copyOf(result);
    }

    public Optional<SelectedPot> current(PotId pot, UserId actor, Selection selection) {
        return readSelected(pot, null, actor, selection);
    }

    public Optional<SelectedPot> componentsAtVersion(
            PotId pot, long version, UserId actor, Selection selection) {
        if (version < 1) throw new IllegalArgumentException("Positive version required");
        return readSelected(pot, version, actor, selection);
    }

    private Optional<SelectedPot> readSelected(
            PotId pot, Long requestedVersion, UserId actor, Selection selection) {
        return SpringAttemptTransactions.participating(
                rows.dataSource,
                () -> {
                    rows.requireTransaction(true);
                    Objects.requireNonNull(actor);
                    Objects.requireNonNull(selection);
                    var metadata = rows.version(pot, requestedVersion);
                    if (metadata == null) return Optional.empty();
                    long version =
                            requestedVersion == null ? number(metadata, "version") : requestedVersion;
                    long latest =
                            requestedVersion == null ? version : number(metadata, "latest");
                    var scope = new VersionScope(pot, version, latest);
                    return Optional.of(selected(scope, actor, selection));
                });
    }

    private SelectedPot selected(VersionScope scope, UserId actor, Selection selection) {
        var potBirth = Map.of(scope.pot().value(), 1L);
        var header =
                potHeader(
                        rows.component(
                                        "pot_header",
                                        scope.pot(),
                                        scope.version(),
                                        scope.latest(),
                                        potBirth)
                                .get(scope.pot().value()));
        var detailRow =
                rows.component(
                                "pot_details",
                                scope.pot(),
                                scope.version(),
                                scope.latest(),
                                potBirth,
                                selection.potDetails() ? "*" : "creator_id")
                        .get(scope.pot().value());
        var shareholderIndex = loadShareholderIndex(scope);
        boolean attached = isActorAttached(actor, shareholderIndex.headers(), scope);
        var auth =
                new AuthorizationFacts(
                        scope.pot(),
                        scope.version(),
                        new UserId(uuid(detailRow, "creator_id")),
                        actor,
                        attached);
        var selectedShareholders = selectedShareholders(scope, selection, shareholderIndex);
        var selectedExpenses = selectedExpenses(scope, selection);
        validateSelectedReferences(selectedExpenses, shareholderIndex);
        ComponentRead<PotDetails> selectedDetails =
                selection.potDetails()
                        ? new ComponentRead.Present<>(potDetails(detailRow))
                        : new ComponentRead.NotLoaded<>();
        return new SelectedPot(
                new PotFacts(scope.pot(), scope.version(), header, auth),
                selectedDetails,
                selectedShareholders,
                selectedExpenses);
    }

    private ShareholderIndex loadShareholderIndex(VersionScope scope) {
        var allBirths = rows.identities("shareholder", scope.pot(), null, scope.version());
        var allHeaders =
                rows.component(
                        "shareholder_header",
                        scope.pot(),
                        scope.version(),
                        scope.latest(),
                        allBirths);
        return new ShareholderIndex(allBirths, allHeaders);
    }

    private boolean isActorAttached(
            UserId actor, Map<UUID, Map<String, Object>> allHeaders, VersionScope scope) {
        // Authorization needs active attachment facts; weights and Expense payloads are not read
        // here.
        var allBirths =
                allHeaders.isEmpty()
                        ? Map.<UUID, Long>of()
                        : rows.identities("shareholder", scope.pot(), allHeaders.keySet(), scope.version());
        var allDetails =
                rows.component(
                        "shareholder_details",
                        scope.pot(),
                        scope.version(),
                        scope.latest(),
                        allBirths,
                        "user_id");
        return allHeaders.entrySet().stream()
                .anyMatch(
                        entry ->
                                !(boolean) entry.getValue().get("deleted")
                                        && actor.value()
                                                .equals(
                                                        allDetails
                                                                .get(entry.getKey())
                                                                .get("user_id")));
    }

    private Map<ShareholderId, SelectedShareholder> selectedShareholders(
            VersionScope scope, Selection selection, ShareholderIndex index) {
        var selectedBirths = new LinkedHashMap<UUID, Long>();
        for (var id : selection.shareholders()) {
            if (index.births().containsKey(id.value())) {
                selectedBirths.put(id.value(), index.births().get(id.value()));
            }
        }
        Map<UUID, Map<String, Object>> details =
                selection.shareholderDetails()
                        ? rows.component(
                                "shareholder_details",
                                scope.pot(),
                                scope.version(),
                                scope.latest(),
                                selectedBirths)
                        : Map.of();
        var weights =
                selection.shareholderWeights()
                        ? rows.component(
                                "shareholder_weight",
                                scope.pot(),
                                scope.version(),
                                scope.latest(),
                                selectedBirths)
                        : Map.<UUID, Map<String, Object>>of();
        var result = new LinkedHashMap<ShareholderId, SelectedShareholder>();
        for (var id : selection.shareholders()) {
            var raw = index.headers().get(id.value());
            var reference =
                    new ShareholderReference(
                            scope.pot(),
                            scope.version(),
                            id,
                            raw == null ? 0L : index.births().get(id.value()),
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
            VersionScope scope, Selection selection) {
        var ids =
                selection.expenses().stream()
                        .map(ExpenseId::value)
                        .collect(java.util.stream.Collectors.toSet());
        var births = rows.identities("expense", scope.pot(), ids, scope.version());
        var headers =
                rows.component(
                        "expense_header", scope.pot(), scope.version(), scope.latest(), births);
        var details =
                selection.expenseDetails()
                        ? rows.component(
                                "expense_details",
                                scope.pot(),
                                scope.version(),
                                scope.latest(),
                                births)
                        : Map.<UUID, Map<String, Object>>of();
        var financialRows =
                selection.expenseFinancials()
                        ? rows.component(
                                "expense_financials",
                                scope.pot(),
                                scope.version(),
                                scope.latest(),
                                births)
                        : Map.<UUID, Map<String, Object>>of();
        var financials = financials(financialRows);
        var result = new LinkedHashMap<ExpenseId, SelectedExpense>();
        for (var id : selection.expenses()) {
            ComponentRead<ExpenseFinancials> f =
                    readFinancialComponent(selection.expenseFinancials(), financials.get(id.value()));
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

    private static ComponentRead<ExpenseFinancials> readFinancialComponent(
            boolean loaded, Versioned<ExpenseFinancials> revision) {
        if (!loaded) return new ComponentRead.NotLoaded<>();
        if (revision == null) return new ComponentRead.Absent<>();
        return new ComponentRead.Present<>(revision);
    }

    public ActiveShareholderUsage activeUsage(PotId pot, long version, ShareholderId shareholder) {
        return SpringAttemptTransactions.participating(
                rows.dataSource,
                () -> {
                    rows.requireTransaction(true);
                    var scope = requireUsageScope(pot, version);
                    var shareholderIndex = loadVerifiedShareholders(scope, shareholder);
                    var births = rows.identities("expense", pot, null, version);
                    var headers =
                            rows.component("expense_header", pot, version, scope.latest(), births);
                    var financials =
                            financials(
                                    rows.component(
                                            "expense_financials",
                                            pot,
                                            version,
                                            scope.latest(),
                                            births));
                    verifyExhaustiveExpenseReferences(financials, headers, shareholderIndex);
                    boolean referenced = isShareholderReferenced(shareholder, headers, financials);
                    return new ActiveShareholderUsage(pot, version, shareholder, referenced, true);
                });
    }

    private VersionScope requireUsageScope(PotId pot, long version) {
        if (version < 1) throw bad("Unknown usage version");
        var metadata = rows.version(pot, version);
        if (metadata == null) throw bad("Unknown usage version");
        return new VersionScope(pot, version, number(metadata, "latest"));
    }

    private ShareholderIndex loadVerifiedShareholders(
            VersionScope scope, ShareholderId shareholder) {
        var index = loadShareholderIndex(scope);
        var birth = index.births().get(shareholder.value());
        if (birth == null || birth > scope.version()) {
            throw bad("Unknown usage subject");
        }
        return index;
    }

    private static void verifyExhaustiveExpenseReferences(
            Map<UUID, Versioned<ExpenseFinancials>> financials,
            Map<UUID, Map<String, Object>> headers,
            ShareholderIndex shareholderIndex) {
        for (var entry : financials.entrySet()) {
            var financial = entry.getValue();
            boolean expenseDeleted = (boolean) headers.get(entry.getKey()).get("deleted");
            for (var id : allReferencedShareholders(financial.value())) {
                var reference = shareholderIndex.headers().get(id.value());
                if (isInvalidShareholderReference(
                        reference,
                        shareholderIndex.births().get(id.value()),
                        financial.activeFrom(),
                        expenseDeleted)) {
                    throw bad("Inconsistent Expense in exhaustive usage scope");
                }
            }
        }
    }

    private static boolean isShareholderReferenced(
            ShareholderId shareholder,
            Map<UUID, Map<String, Object>> headers,
            Map<UUID, Versioned<ExpenseFinancials>> financials) {
        return headers.entrySet().stream()
                .filter(entry -> !(boolean) entry.getValue().get("deleted"))
                .map(entry -> financials.get(entry.getKey()).value())
                .anyMatch(
                        f ->
                                f.payerId().equals(shareholder)
                                        || f.participants().containsKey(shareholder));
    }

    private Map<UUID, Versioned<ExpenseFinancials>> financials(
            Map<UUID, Map<String, Object>> financialRows) {
        if (financialRows.isEmpty()) return Map.of();
        var participants = loadParticipantsByRevision(financialRows);
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

    private Map<Long, Map<ShareholderId, Optional<Weight>>> loadParticipantsByRevision(
            Map<UUID, Map<String, Object>> financialRows) {
        var revisionIds =
                financialRows.values().stream().map(row -> number(row, "revision_id")).toList();
        var owners = new HashMap<Long, UUID>();
        financialRows
                .values()
                .forEach(row -> owners.put(number(row, "revision_id"), uuid(row, "pot_id")));
        var participants = new HashMap<Long, Map<ShareholderId, Optional<Weight>>>();
        for (var row :
                rows.named.queryForList(
                        "select * from v3_expense_participant where financial_revision_id"
                                + " = any(:ids)",
                        Map.of("ids", new SqlArrayValue("bigint", revisionIds.toArray())))) {
            long revId = number(row, "financial_revision_id");
            if (!Objects.equals(owners.get(revId), uuid(row, "pot_id"))) {
                throw bad("Mixed participant owner");
            }
            var map = participants.computeIfAbsent(revId, key -> new LinkedHashMap<>());
            if (map.put(new ShareholderId(uuid(row, "shareholder_id")), toOptionalWeight(row))
                    != null) {
                throw bad("Duplicate persisted participant");
            }
        }
        return participants;
    }

    private static Optional<Weight> toOptionalWeight(Map<String, Object> row) {
        if (row.get("numerator") == null && row.get("denominator") == null) {
            return Optional.empty();
        }
        return Optional.of(new Weight(rational(row)));
    }

    private void validateReferences(
            Map<ShareholderId, ShareholderState> shareholders,
            Map<ExpenseId, ExpenseState> expenses,
            Map<UUID, Long> births) {
        for (var expense : expenses.values()) {
            var f = expense.financials();
            boolean expenseDeleted = expense.header().value().deleted();
            for (var id : allReferencedShareholders(f.value())) {
                var shareholder = shareholders.get(id);
                if (shareholder == null || births.get(id.value()) > f.activeFrom()) {
                    throw bad("Expense references an unavailable Shareholder");
                }
                if (isActiveExpenseReferencingDeleted(
                        expenseDeleted, shareholder.header().value().deleted())) {
                    throw bad("Expense references an unavailable Shareholder");
                }
            }
        }
    }

    private void validateSelectedReferences(
            Map<ExpenseId, SelectedExpense> expenses, ShareholderIndex shareholderIndex) {
        for (var expense : expenses.values()) {
            validateSingleSelectedExpense(expense, shareholderIndex);
        }
    }

    private static void validateSingleSelectedExpense(
            SelectedExpense expense, ShareholderIndex shareholderIndex) {
        if (!(expense.financials() instanceof ComponentRead.Present<ExpenseFinancials> present)) {
            return;
        }
        var financials = present.revision();
        var header = ((ComponentRead.Present<ExpenseHeader>) expense.header()).revision();
        boolean expenseDeleted = header.value().deleted();
        for (var id : allReferencedShareholders(financials.value())) {
            var shareholder = shareholderIndex.headers().get(id.value());
            if (isInvalidShareholderReference(
                    shareholder,
                    shareholderIndex.births().get(id.value()),
                    financials.activeFrom(),
                    expenseDeleted)) {
                throw bad("Invalid selective financial reference");
            }
        }
    }

    private static Set<ShareholderId> allReferencedShareholders(ExpenseFinancials financials) {
        var ids = new HashSet<>(financials.participants().keySet());
        ids.add(financials.payerId());
        return ids;
    }

    private static boolean isInvalidShareholderReference(
            Map<String, Object> shareholderRow,
            Long birth,
            long activeFrom,
            boolean expenseDeleted) {
        if (shareholderRow == null || birth == null) return true;
        if (birth > activeFrom) return true;
        return isActiveExpenseReferencingDeleted(
                expenseDeleted, (boolean) shareholderRow.get("deleted"));
    }

    private static boolean isActiveExpenseReferencingDeleted(
            boolean expenseDeleted, boolean shareholderDeleted) {
        return !expenseDeleted && shareholderDeleted;
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

    public static Fraction rational(Map<String, Object> row) {
        try {
            String numerator = (String) row.get("numerator");
            String denominator = (String) row.get("denominator");
            if ("0".equals(numerator) && "1".equals(denominator)) return Fraction.ZERO;
            if ("1".equals(numerator) && "1".equals(denominator)) return Fraction.ONE;
            return parseCanonicalFraction(numerator, denominator);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw bad("Invalid persisted rational");
        }
    }

    private static Fraction parseCanonicalFraction(String numerator, String denominator) {
        var fraction = new Fraction(new BigInteger(numerator), new BigInteger(denominator));
        if (!fraction.numerator().toString().equals(numerator)
                || !fraction.denominator().toString().equals(denominator)) {
            throw bad("Noncanonical persisted rational");
        }
        return fraction;
    }
}
