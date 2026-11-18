package com.kartaguez.pocoma.infra.postgres.source;
import static com.kartaguez.pocoma.infra.postgres.core.history.PotHistoryRows.bad;
import static com.kartaguez.pocoma.infra.postgres.core.history.PotHistoryRows.number;
import static com.kartaguez.pocoma.infra.postgres.core.history.PotHistoryRows.uuid;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.support.SqlArrayValue;

import com.kartaguez.pocoma.domain.pot.value.Amount;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseDetails;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseFinancials;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseHeader;
import com.kartaguez.pocoma.domain.pot.PotDetails;
import com.kartaguez.pocoma.domain.pot.PotHeader;
import com.kartaguez.pocoma.domain.pot.shareholder.ShareholderDetails;
import com.kartaguez.pocoma.domain.pot.shareholder.ShareholderHeader;
import com.kartaguez.pocoma.domain.pot.shareholder.ShareholderWeight;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.PotVersion;
import com.kartaguez.pocoma.domain.projection.SubobjectContent;
import com.kartaguez.pocoma.domain.projection.SubobjectContent.ExpenseDetailsContent;
import com.kartaguez.pocoma.domain.projection.SubobjectContent.ExpenseFinancialsContent;
import com.kartaguez.pocoma.domain.projection.SubobjectContent.ExpenseHeaderContent;
import com.kartaguez.pocoma.domain.projection.SubobjectContent.PotDetailsContent;
import com.kartaguez.pocoma.domain.projection.SubobjectContent.PotHeaderContent;
import com.kartaguez.pocoma.domain.projection.SubobjectContent.ShareholderDetailsContent;
import com.kartaguez.pocoma.domain.projection.SubobjectContent.ShareholderHeaderContent;
import com.kartaguez.pocoma.domain.projection.SubobjectContent.ShareholderWeightContent;
import com.kartaguez.pocoma.domain.projection.SubobjectKey;
import com.kartaguez.pocoma.domain.projection.SubobjectKind;
import com.kartaguez.pocoma.domain.projection.SubobjectVersion;
import com.kartaguez.pocoma.engine.materialize.pot.PotVersionSource;
import com.kartaguez.pocoma.infra.postgres.core.history.PostgresPotHistoryReader;
import com.kartaguez.pocoma.infra.postgres.core.history.PotHistoryRows;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

/** Lightweight composition and separately selected immutable contents; never reads READ. */
public final class JdbcPotVersionSource implements PotVersionSource {
    private final PotHistoryRows rows;
    public JdbcPotVersionSource(DataSource source) { rows=new PotHistoryRows(source); }
    public PotVersion composition(UUID event, RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(
                rows.dataSource,
                context,
                () -> {
                    rows.requireTransaction(true);
                    var eventRows =
                            rows.jdbc.queryForList(
                                    "select e.pot_id,e.subject_version from v3_pot_event e join"
                                        + " v3_event_position p on p.event_id=e.event_id and"
                                        + " p.family='POT' join v3_event_counter c on"
                                        + " c.family=p.family and c.position>=p.position where"
                                        + " e.event_id=? and e.subject_type='POT'",
                                    event);
                    if (eventRows.size() != 1) throw bad("Missing exact published Pot event");
                    var e = eventRows.getFirst();
                    var pot = new PotId(uuid(e, "pot_id"));
                    long v = number(e, "subject_version");
                    var metadata = rows.version(pot, v);
                    if (metadata == null) throw bad("Missing event version");
                    long latest = number(metadata, "latest");
                    var h =
                            rows.component("pot_header", pot, v, latest, Map.of(pot.value(), 1L))
                                    .get(pot.value());
                    var d =
                            rows.component(
                                            "pot_details",
                                            pot,
                                            v,
                                            latest,
                                            Map.of(pot.value(), 1L),
                                            "creator_id")
                                    .get(pot.value());
                    var attached = new HashSet<UUID>();
                    var ss = loadShareholders(pot, v, latest, attached);
                    var es = loadExpenses(pot, v, latest);
                    var auth = new HashSet<>(attached);
                    auth.add(uuid(d, "creator_id"));
                    return new PotVersion(
                            pot.value(),
                            v,
                            number(h, "local_version"),
                            number(d, "local_version"),
                            (boolean) h.get("deleted"),
                            ss,
                            es,
                            auth,
                            attached);
                });
    }

    private List<PotVersion.Shareholder> loadShareholders(
            PotId pot, long v, long latest, Set<UUID> attached) {
        var sb = rows.identities("shareholder", pot, null, v);
        var sh = rows.component("shareholder_header", pot, v, latest, sb);
        var sd = rows.component("shareholder_details", pot, v, latest, sb, "user_id");
        var sw = rows.component("shareholder_weight", pot, v, latest, sb, "local_version");
        var ss = new ArrayList<PotVersion.Shareholder>();
        for (var id : sb.keySet()) {
            boolean deleted = (boolean) sh.get(id).get("deleted");
            UUID user = uuid(sd.get(id), "user_id");
            ss.add(
                    new PotVersion.Shareholder(
                            id,
                            number(sh.get(id), "local_version"),
                            number(sd.get(id), "local_version"),
                            number(sw.get(id), "local_version"),
                            deleted,
                            user == null));
            if (!deleted && user != null) attached.add(user);
        }
        return ss;
    }

    private List<PotVersion.Expense> loadExpenses(PotId pot, long v, long latest) {
        var eb = rows.identities("expense", pot, null, v);
        var eh = rows.component("expense_header", pot, v, latest, eb);
        var ed = rows.component("expense_details", pot, v, latest, eb, "local_version");
        var ef = rows.component("expense_financials", pot, v, latest, eb, "local_version");
        var es = new ArrayList<PotVersion.Expense>();
        for (var id : eb.keySet()) {
            es.add(
                    new PotVersion.Expense(
                            id,
                            number(eh.get(id), "local_version"),
                            number(ed.get(id), "local_version"),
                            number(ef.get(id), "local_version"),
                            (boolean) eh.get(id).get("deleted")));
        }
        return es;
    }

    public List<SubobjectVersion> contents(
            PotVersion composition,
            Collection<SubobjectKey> requested,
            RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(
                rows.dataSource,
                context,
                () -> {
                    rows.requireTransaction(true);
                    var unique = new TreeSet<SubobjectKey>(requested);
                    if (unique.size() != requested.size()
                            || !new HashSet<>(composition.keys()).containsAll(unique)) {
                        throw bad("Invalid requested component keys");
                    }
                    var result = new ArrayList<SubobjectVersion>();
                    for (var kind : SubobjectKind.values()) {
                        var keys = unique.stream().filter(k -> k.kind() == kind).toList();
                        if (keys.isEmpty()) continue;
                        loadKindContents(composition, kind, keys, result);
                    }
                    result.sort(Comparator.comparing(SubobjectVersion::key));
                    return List.copyOf(result);
                });
    }

    private void loadKindContents(
            PotVersion composition,
            SubobjectKind kind,
            List<SubobjectKey> keys,
            List<SubobjectVersion> result) {
        var args = new ArrayList<Object>();
        args.add(composition.potId());
        args.add(composition.version());
        args.add(composition.version());
        var tuples = new ArrayList<String>();
        for (var k : keys) {
            tuples.add("(?,?)");
            args.add(k.objectId());
            args.add(k.localVersion());
        }
        String table = tableFor(kind);
        var data =
                rows.jdbc.queryForList(
                        "select * from v3_"
                                + table
                                + " where pot_id=? and active_from<=? and (active_to is null or"
                                + " ?<active_to) and (object_id,local_version) in ("
                                + String.join(",", tuples)
                                + ")",
                        args.toArray());
        if (data.size() != keys.size()) throw bad("Missing exact component contents");
        var participants =
                kind == SubobjectKind.EXPENSE_FINANCIALS
                        ? loadFinancialParticipants(composition, data)
                        : Map.<Long, Map<ShareholderId, Optional<Weight>>>of();
        var lookup = new FinancialLookup(composition, participants);
        var pot = new PotId(composition.potId());
        for (var r : data) {
            var id = uuid(r, "object_id");
            var key = new SubobjectKey(pot.value(), kind, id, number(r, "local_version"));
            result.add(new SubobjectVersion(key, toSubobjectContent(key, r, lookup)));
        }
    }

    private record FinancialLookup(
            PotVersion composition, Map<Long, Map<ShareholderId, Optional<Weight>>> participants) {}

    private static final Map<SubobjectKind, String> KIND_TABLES =
            Map.of(
                    SubobjectKind.POT_HEADER, "pot_header",
                    SubobjectKind.POT_DETAILS, "pot_details",
                    SubobjectKind.SHAREHOLDER_HEADER, "shareholder_header",
                    SubobjectKind.SHAREHOLDER_DETAILS, "shareholder_details",
                    SubobjectKind.SHAREHOLDER_WEIGHT, "shareholder_weight",
                    SubobjectKind.EXPENSE_HEADER, "expense_header",
                    SubobjectKind.EXPENSE_DETAILS, "expense_details",
                    SubobjectKind.EXPENSE_FINANCIALS, "expense_financials");

    private static String tableFor(SubobjectKind kind) {
        return KIND_TABLES.get(kind);
    }

    private Map<Long, Map<ShareholderId, Optional<Weight>>> loadFinancialParticipants(
            PotVersion composition, List<Map<String, Object>> data) {
        var participants = new HashMap<Long, Map<ShareholderId, Optional<Weight>>>();
        var revisions = data.stream().map(r -> number(r, "revision_id")).toArray();
        for (var p :
                rows.named.queryForList(
                        "select * from v3_expense_participant where"
                                + " financial_revision_id=any(:ids)",
                        Map.of("ids", new SqlArrayValue("bigint", revisions)))) {
            if (!composition.potId().equals(uuid(p, "pot_id"))) {
                throw bad("Foreign financial participant");
            }
            var map =
                    participants.computeIfAbsent(
                            number(p, "financial_revision_id"), x -> new HashMap<>());
            Optional<Weight> w =
                    p.get("numerator") == null && p.get("denominator") == null
                            ? Optional.empty()
                            : Optional.of(new Weight(PostgresPotHistoryReader.rational(p)));
            if (map.put(new ShareholderId(uuid(p, "shareholder_id")), w) != null) {
                throw bad("Duplicate participant");
            }
        }
        return participants;
    }

    private static SubobjectContent toSubobjectContent(
            SubobjectKey key, Map<String, Object> r, FinancialLookup lookup) {
        var pot = new PotId(key.potId());
        var id = key.objectId();
        return switch (key.kind()) {
            case POT_HEADER -> new PotHeaderContent(new PotHeader(pot, (boolean) r.get("deleted")));
            case POT_DETAILS ->
                    new PotDetailsContent(
                            new PotDetails(
                                    pot,
                                    new Label((String) r.get("label")),
                                    new UserId(uuid(r, "creator_id"))));
            case SHAREHOLDER_HEADER ->
                    new ShareholderHeaderContent(
                            new ShareholderHeader(
                                    pot, new ShareholderId(id), (boolean) r.get("deleted")));
            case SHAREHOLDER_DETAILS ->
                    new ShareholderDetailsContent(
                            new ShareholderDetails(
                                    pot,
                                    new ShareholderId(id),
                                    new Name((String) r.get("name")),
                                    Optional.ofNullable(uuid(r, "user_id")).map(UserId::new)));
            case SHAREHOLDER_WEIGHT ->
                    new ShareholderWeightContent(
                            new ShareholderWeight(
                                    pot,
                                    new ShareholderId(id),
                                    new Weight(PostgresPotHistoryReader.rational(r))));
            case EXPENSE_HEADER ->
                    new ExpenseHeaderContent(
                            new ExpenseHeader(pot, new ExpenseId(id), (boolean) r.get("deleted")));
            case EXPENSE_DETAILS ->
                    new ExpenseDetailsContent(
                            new ExpenseDetails(
                                    pot,
                                    new ExpenseId(id),
                                    new Label((String) r.get("label")),
                                    ((java.sql.Date) r.get("expense_date")).toLocalDate()));
            case EXPENSE_FINANCIALS -> buildFinancialsContent(pot, id, r, lookup);
        };
    }

    private static SubobjectContent buildFinancialsContent(
            PotId pot, UUID id, Map<String, Object> r, FinancialLookup lookup) {
        var shares = lookup.participants().get(number(r, "revision_id"));
        if (shares == null || shares.isEmpty()) throw bad("Missing financial participants");
        var payer = new ShareholderId(uuid(r, "payer_id"));
        var refs = new HashSet<>(shares.keySet());
        refs.add(payer);
        verifyShareholderRefs(lookup.composition(), id, refs);
        return new ExpenseFinancialsContent(
                new ExpenseFinancials(
                        pot,
                        new ExpenseId(id),
                        new Amount(PostgresPotHistoryReader.rational(r)),
                        payer,
                        shares));
    }

    private static void verifyShareholderRefs(
            PotVersion composition, UUID expenseId, Set<ShareholderId> refs) {
        boolean deleted =
                composition.expenses().stream()
                        .filter(e -> e.id().equals(expenseId))
                        .findFirst()
                        .orElseThrow()
                        .deleted();
        for (var ref : refs) {
            boolean valid =
                    composition.shareholders().stream()
                            .anyMatch(s -> s.id().equals(ref.value()) && (deleted || !s.deleted()));
            if (!valid) throw bad("Invalid financial reference");
        }
    }
}
