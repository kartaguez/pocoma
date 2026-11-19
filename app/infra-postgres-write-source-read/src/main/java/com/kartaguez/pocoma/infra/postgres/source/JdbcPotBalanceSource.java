package com.kartaguez.pocoma.infra.postgres.source;

import static com.kartaguez.pocoma.infra.postgres.core.history.PotHistoryRows.bad;
import static com.kartaguez.pocoma.infra.postgres.core.history.PotHistoryRows.number;
import static com.kartaguez.pocoma.infra.postgres.core.history.PotHistoryRows.uuid;

import com.kartaguez.pocoma.domain.pot.PotFinancialState;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseFinancials;
import com.kartaguez.pocoma.domain.pot.value.Amount;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.SubobjectKey;
import com.kartaguez.pocoma.engine.materialize.pot.PotBalanceSource;
import com.kartaguez.pocoma.infra.postgres.core.history.PostgresPotHistoryReader;
import com.kartaguez.pocoma.infra.postgres.core.history.PotHistoryRows;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.support.SqlArrayValue;

public final class JdbcPotBalanceSource implements PotBalanceSource {
    private final PotHistoryRows rows;
    private final PostgresPotHistoryReader history;

    public JdbcPotBalanceSource(DataSource source) {
        this.rows = new PotHistoryRows(source);
        this.history = new PostgresPotHistoryReader(source);
    }

    @Override
    public PotFinancialState load(UUID event, RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(
                rows.dataSource,
                context,
                () -> {
                    rows.requireTransaction(true);
                    var ref = loadPublishedEvent(event);
                    return history.financialAtVersion(new PotId(ref.potId()), ref.version())
                            .orElseThrow(() -> new IllegalStateException("Missing historical finance"));
                });
    }

    @Override
    public EventRef eventRef(UUID event, RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(
                rows.dataSource,
                context,
                () -> {
                    rows.requireTransaction(true);
                    var published = loadPublishedEvent(event);
                    var pot = new PotId(published.potId());
                    var metadata = rows.version(pot, published.version());
                    if (metadata == null) {
                        throw bad("Missing target Pot version");
                    }
                    long latest = number(metadata, "latest");
                    var header =
                            rows.component(
                                            "pot_header",
                                            pot,
                                            published.version(),
                                            latest,
                                            Map.of(pot.value(), 1L))
                                    .get(pot.value());
                    boolean deleted = (boolean) header.get("deleted");
                    return new EventRef(published.potId(), published.version(), deleted);
                });
    }

    @Override
    public FinancialDelta financialDelta(
            UUID potId,
            long afterVersionExclusive,
            long targetVersionInclusive,
            int maxChangedExpenses,
            RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(
                rows.dataSource,
                context,
                () -> {
                    rows.requireTransaction(true);
                    if (afterVersionExclusive < 1
                            || targetVersionInclusive <= afterVersionExclusive
                            || maxChangedExpenses < 0) {
                        throw new IllegalArgumentException("Invalid financial delta bounds");
                    }
                    var pot = new PotId(potId);
                    var metadata = rows.version(pot, targetVersionInclusive);
                    if (metadata == null) {
                        throw bad("Missing target Pot version");
                    }
                    long latest = number(metadata, "latest");

                    var shHeaders =
                            loadDeltaRows(
                                    "shareholder_header",
                                    "object_id,deleted,active_from,active_to",
                                    potId,
                                    afterVersionExclusive,
                                    targetVersionInclusive,
                                    latest);
                    for (var row : shHeaders.values()) {
                        if ((boolean) row.get("deleted")) {
                            return new FinancialDelta(true, 0, Map.of(), List.of(), List.of());
                        }
                    }

                    var shWeightRows =
                            loadDeltaRows(
                                    "shareholder_weight",
                                    "object_id,numerator,denominator,active_from,active_to",
                                    potId,
                                    afterVersionExclusive,
                                    targetVersionInclusive,
                                    latest);
                    var updatedOrAddedWeights = new HashMap<UUID, Weight>(shWeightRows.size() * 2);
                    shWeightRows.forEach(
                            (id, row) ->
                                    updatedOrAddedWeights.put(
                                            id, new Weight(PostgresPotHistoryReader.rational(row))));

                    var expHeadersN =
                            loadDeltaRows(
                                    "expense_header",
                                    "object_id,deleted,active_from,active_to",
                                    potId,
                                    afterVersionExclusive,
                                    targetVersionInclusive,
                                    latest);
                    var expFinN =
                            loadDeltaRows(
                                    "expense_financials",
                                    "revision_id,object_id,numerator,denominator,payer_id,active_from,active_to",
                                    potId,
                                    afterVersionExclusive,
                                    targetVersionInclusive,
                                    latest);

                    var changedExpenseIds = new TreeSet<UUID>(SubobjectKey::compareUuid);
                    changedExpenseIds.addAll(expHeadersN.keySet());
                    changedExpenseIds.addAll(expFinN.keySet());
                    if (changedExpenseIds.isEmpty()) {
                        return new FinancialDelta(
                                false, 0, updatedOrAddedWeights, List.of(), List.of());
                    }
                    if (changedExpenseIds.size() > maxChangedExpenses) {
                        return new FinancialDelta(
                                false,
                                changedExpenseIds.size(),
                                updatedOrAddedWeights,
                                List.of(),
                                List.of());
                    }

                    var idArray = changedExpenseIds.toArray(UUID[]::new);
                    var expHeadersK =
                            loadRowsAtVersion(
                                    "expense_header",
                                    "object_id,deleted,active_from,active_to",
                                    potId,
                                    idArray,
                                    afterVersionExclusive,
                                    latest);
                    var expFinK =
                            loadRowsAtVersion(
                                    "expense_financials",
                                    "revision_id,object_id,numerator,denominator,payer_id,active_from,active_to",
                                    potId,
                                    idArray,
                                    afterVersionExclusive,
                                    latest);
                    if (!expHeadersK.keySet().equals(expFinK.keySet())) {
                        throw bad("Inconsistent expense state at reference version");
                    }

                    var toRemoveRows = new ArrayList<Map<String, Object>>();
                    var toAddRows = new ArrayList<Map<String, Object>>();
                    var neededRevisionIds = new LinkedHashSet<Long>();

                    for (UUID expId : changedExpenseIds) {
                        var headerK = expHeadersK.get(expId);
                        var finK = expFinK.get(expId);
                        boolean activeAtK = headerK != null && !(boolean) headerK.get("deleted");

                        var headerN = expHeadersN.getOrDefault(expId, headerK);
                        var finN = expFinN.getOrDefault(expId, finK);
                        if (headerN == null || finN == null) {
                            throw bad("Incomplete expense revision at target version");
                        }
                        boolean activeAtN = !(boolean) headerN.get("deleted");

                        if (activeAtK && activeAtN
                                && number(finK, "revision_id") == number(finN, "revision_id")) {
                            continue;
                        }
                        if (activeAtK) {
                            toRemoveRows.add(finK);
                            neededRevisionIds.add(number(finK, "revision_id"));
                        }
                        if (activeAtN) {
                            toAddRows.add(finN);
                            neededRevisionIds.add(number(finN, "revision_id"));
                        }
                    }

                    var participantsByRevision =
                            neededRevisionIds.isEmpty()
                                    ? Map.<Long, Map<ShareholderId, Optional<Weight>>>of()
                                    : loadParticipants(potId, neededRevisionIds);

                    var removed = toExpenseFinancials(pot, toRemoveRows, participantsByRevision);
                    var added = toExpenseFinancials(pot, toAddRows, participantsByRevision);
                    return new FinancialDelta(
                            false,
                            changedExpenseIds.size(),
                            updatedOrAddedWeights,
                            removed,
                            added);
                });
    }

    private record PublishedEvent(UUID potId, long version) {}

    private PublishedEvent loadPublishedEvent(UUID event) {
        var found =
                rows.jdbc.queryForList(
                        "select e.pot_id,e.subject_version from v3_pot_event e join"
                            + " v3_event_position p on p.event_id=e.event_id and"
                            + " p.family='POT' join v3_event_counter c on"
                            + " c.family=p.family and c.position>=p.position where"
                            + " e.event_id=? and e.subject_type='POT'",
                        event);
        if (found.size() != 1) {
            throw new IllegalStateException("Missing exact Balance event");
        }
        var row = found.getFirst();
        return new PublishedEvent(uuid(row, "pot_id"), number(row, "subject_version"));
    }

    private Map<UUID, Map<String, Object>> loadDeltaRows(
            String table,
            String columns,
            UUID potId,
            long afterExclusive,
            long targetInclusive,
            long latest) {
        var found =
                rows.jdbc.queryForList(
                        "select "
                                + columns
                                + " from v3_"
                                + table
                                + " where pot_id=? and active_from>? and active_from<=?"
                                + " and (active_to is null or active_to>?)",
                        potId,
                        afterExclusive,
                        targetInclusive,
                        targetInclusive);
        if (found.isEmpty()) {
            return Map.of();
        }
        var result = new HashMap<UUID, Map<String, Object>>(found.size() * 2);
        for (var row : found) {
            long from = number(row, "active_from");
            Long to = (Long) row.get("active_to");
            if (from <= afterExclusive
                    || from > targetInclusive
                    || (to != null && (to <= targetInclusive || to > latest))) {
                throw bad("Invalid applicable delta revision in " + table);
            }
            UUID id = uuid(row, "object_id");
            if (result.put(id, row) != null) {
                throw bad("Multiple applicable delta revisions in " + table);
            }
        }
        return result;
    }

    private Map<UUID, Map<String, Object>> loadRowsAtVersion(
            String table,
            String columns,
            UUID potId,
            UUID[] objectIds,
            long version,
            long latest) {
        var found =
                rows.named.queryForList(
                        "select "
                                + columns
                                + " from v3_"
                                + table
                                + " where pot_id=:pot and object_id=any(:ids)"
                                + " and active_from<=:v and (active_to is null or active_to>:v)",
                        Map.of(
                                "pot", potId,
                                "ids", new SqlArrayValue("uuid", objectIds),
                                "v", version));
        if (found.isEmpty()) {
            return Map.of();
        }
        var result = new HashMap<UUID, Map<String, Object>>(found.size() * 2);
        for (var row : found) {
            long from = number(row, "active_from");
            Long to = (Long) row.get("active_to");
            if (from < 1 || from > version || (to != null && (to <= version || to > latest))) {
                throw bad("Invalid applicable reference revision in " + table);
            }
            UUID id = uuid(row, "object_id");
            if (result.put(id, row) != null) {
                throw bad("Multiple applicable reference revisions in " + table);
            }
        }
        return result;
    }

    private Map<Long, Map<ShareholderId, Optional<Weight>>> loadParticipants(
            UUID potId, Collection<Long> revisionIds) {
        var participants = new HashMap<Long, Map<ShareholderId, Optional<Weight>>>(revisionIds.size() * 2);
        for (var p :
                rows.named.queryForList(
                        "select financial_revision_id,pot_id,shareholder_id,numerator,denominator"
                            + " from v3_expense_participant where"
                            + " financial_revision_id=any(:ids)",
                        Map.of("ids", new SqlArrayValue("bigint", revisionIds.toArray())))) {
            if (!potId.equals(uuid(p, "pot_id"))) {
                throw bad("Foreign financial participant");
            }
            var map =
                    participants.computeIfAbsent(
                            number(p, "financial_revision_id"), ignored -> new HashMap<>());
            Optional<Weight> weight =
                    p.get("numerator") == null && p.get("denominator") == null
                            ? Optional.empty()
                            : Optional.of(new Weight(PostgresPotHistoryReader.rational(p)));
            if (map.put(new ShareholderId(uuid(p, "shareholder_id")), weight) != null) {
                throw bad("Duplicate participant");
            }
        }
        return participants;
    }

    private static List<ExpenseFinancials> toExpenseFinancials(
            PotId pot,
            List<Map<String, Object>> finRows,
            Map<Long, Map<ShareholderId, Optional<Weight>>> participantsByRevision) {
        if (finRows.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<ExpenseFinancials>(finRows.size());
        for (var row : finRows) {
            long revId = number(row, "revision_id");
            var participants = participantsByRevision.get(revId);
            if (participants == null || participants.isEmpty()) {
                throw bad("Missing financial participants for revision " + revId);
            }
            result.add(
                    new ExpenseFinancials(
                            pot,
                            new ExpenseId(uuid(row, "object_id")),
                            new Amount(PostgresPotHistoryReader.rational(row)),
                            new ShareholderId(uuid(row, "payer_id")),
                            participants));
        }
        return List.copyOf(result);
    }
}
