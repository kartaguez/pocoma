package com.kartaguez.pocoma.compose.polling.materialize.maintenance;

import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition;
import com.kartaguez.pocoma.domain.consumption.ConsumptionCatalog;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/**
 * Evaluates the mathematical readiness predicate P_READY(B) = C_checkpoints & C_slots & C_integrity.
 * Canonical, conservative, and strictly derived from the consumption catalog.
 */
public final class ReadinessOracle {

    public record CheckpointKey(UUID consumptionId, int partitionId) {}

    public static final Set<CheckpointKey> EXPECTED_CHECKPOINT_KEYS;
    public static final Map<UUID, String> CANONICAL_READ_CONSUMPTION_FAMILIES;
    public static final Set<String> EXPECTED_WRITE_FAMILIES = Set.of(
            "POT", "IDENTITY", "COMMAND_COMPLETION", "IDENTITY_COMPLETION", "INVITATION"
    );

    static {
        Set<CheckpointKey> keys = new HashSet<>();
        Map<UUID, String> fams = new HashMap<>();
        for (ConsumptionDefinition def : ConsumptionCatalog.definitions()) {
            if (def.authority() == ConsumptionDefinition.Authority.READ) {
                UUID cid = def.id().value();
                fams.put(cid, def.family().name());
                for (int p = 0; p < 4; p++) {
                    keys.add(new CheckpointKey(cid, p));
                }
            }
        }
        EXPECTED_CHECKPOINT_KEYS = Collections.unmodifiableSet(keys);
        CANONICAL_READ_CONSUMPTION_FAMILIES = Collections.unmodifiableMap(fams);
    }

    public static final int EXPECTED_CHECKPOINTS_COUNT = EXPECTED_CHECKPOINT_KEYS.size(); // 28

    public record Evaluation(
            boolean ready,
            boolean checkpointsSatisfied,
            int satisfiedCheckpointsCount,
            int expectedCheckpointsCount,
            boolean slotsSatisfied,
            int residualSlotsCount,
            int quarantinedSlotsCount,
            boolean integritySatisfied,
            String diagnostic) {}

    public record CheckpointEntry(UUID consumptionId, String family, int partitionId, long position) {}
    public record SlotEntry(UUID consumptionId, String family, UUID consumableId, long position, String state) {}

    /**
     * Map WRITE family to READ family and vice-versa.
     */
    public static String toWriteFamily(String family) {
        if (family == null) return null;
        if (family.endsWith("_EVENT")) {
            return family.substring(0, family.length() - "_EVENT".length());
        }
        return family;
    }

    public static String toReadFamily(String family) {
        if (family == null) return null;
        if (family.endsWith("_EVENT") || family.equals("COMMAND")) return family;
        if (family.equals("IDENTITY_REQUEST")) return family;
        return family + "_EVENT";
    }

    private record CheckpointEvaluation(boolean satisfied, int count, String diagnostic) {}

    private record SlotEvaluation(
            boolean satisfied, int residual, int quarantined, String diagnostic) {}

    /**
     * In-memory evaluation method for unit testing and contract verification.
     */
    public Evaluation evaluatePure(
            Map<String, Long> targetPrefix,
            List<CheckpointEntry> checkpoints,
            List<SlotEntry> slots,
            List<String> integrityErrors) {
        Objects.requireNonNull(targetPrefix, "targetPrefix cannot be null");
        Objects.requireNonNull(checkpoints, "checkpoints cannot be null");
        Objects.requireNonNull(slots, "slots cannot be null");
        Objects.requireNonNull(integrityErrors, "integrityErrors cannot be null");

        var cpEval = evaluateCheckpoints(targetPrefix, checkpoints);
        var slotEval = evaluateSlots(targetPrefix, slots);
        boolean integritySatisfied = integrityErrors.isEmpty();
        boolean ready = cpEval.satisfied() && slotEval.satisfied() && integritySatisfied;
        String diagnostic = buildEvaluationDiagnostic(cpEval, slotEval, integrityErrors);

        return new Evaluation(
                ready,
                cpEval.satisfied(),
                cpEval.count(),
                EXPECTED_CHECKPOINTS_COUNT,
                slotEval.satisfied(),
                slotEval.residual(),
                slotEval.quarantined(),
                integritySatisfied,
                diagnostic);
    }

    private CheckpointEvaluation evaluateCheckpoints(
            Map<String, Long> targetPrefix, List<CheckpointEntry> checkpoints) {
        List<String> diagnostics = new ArrayList<>();
        Set<String> missingWriteFamilies = findMissingWriteFamilies(targetPrefix);
        if (!missingWriteFamilies.isEmpty()) {
            diagnostics.add(
                    "Target prefix B missing expected families: " + missingWriteFamilies);
        }
        int satisfied = 0;
        Set<CheckpointKey> seenKeys = new HashSet<>();
        boolean catalogMismatch = false;
        for (CheckpointEntry cp : checkpoints) {
            if (!isUniqueExpectedCheckpoint(cp, seenKeys, diagnostics)) {
                catalogMismatch = true;
                continue;
            }
            if (isCheckpointSatisfied(targetPrefix, cp, diagnostics)) {
                satisfied++;
            }
        }
        if (seenKeys.size() < EXPECTED_CHECKPOINT_KEYS.size()) {
            Set<CheckpointKey> missing = new HashSet<>(EXPECTED_CHECKPOINT_KEYS);
            missing.removeAll(seenKeys);
            diagnostics.add("Missing checkpoints: " + missing.size());
        }
        boolean allSatisfied =
                missingWriteFamilies.isEmpty()
                        && !catalogMismatch
                        && seenKeys.size() == EXPECTED_CHECKPOINTS_COUNT
                        && satisfied == EXPECTED_CHECKPOINTS_COUNT;
        return new CheckpointEvaluation(allSatisfied, satisfied, String.join(", ", diagnostics));
    }

    private static boolean isUniqueExpectedCheckpoint(
            CheckpointEntry cp, Set<CheckpointKey> seenKeys, List<String> diagnostics) {
        CheckpointKey key = new CheckpointKey(cp.consumptionId(), cp.partitionId());
        if (!EXPECTED_CHECKPOINT_KEYS.contains(key)) {
            diagnostics.add(
                    String.format(
                            "Unexpected checkpoint: %s p%d",
                            cp.consumptionId(), cp.partitionId()));
            return false;
        }
        if (!seenKeys.add(key)) {
            diagnostics.add(
                    String.format(
                            "Duplicate checkpoint: %s p%d",
                            cp.consumptionId(), cp.partitionId()));
            return false;
        }
        return true;
    }

    private static Set<String> findMissingWriteFamilies(Map<String, Long> targetPrefix) {
        Set<String> missing = new HashSet<>();
        for (String expectedFam : EXPECTED_WRITE_FAMILIES) {
            if (!targetPrefix.containsKey(expectedFam)) {
                missing.add(expectedFam);
            }
        }
        return missing;
    }

    private static boolean isCheckpointSatisfied(
            Map<String, Long> targetPrefix, CheckpointEntry cp, List<String> diagnostics) {
        Long targetPos = resolveTargetPosition(targetPrefix, cp.family());
        if (targetPos == null) {
            diagnostics.add(
                    String.format(
                            "Checkpoint %s p%d family '%s' not found in target prefix",
                            cp.consumptionId(), cp.partitionId(), cp.family()));
            return false;
        }
        if (cp.position() >= targetPos) return true;
        diagnostics.add(
                String.format(
                        "Checkpoint %s p%d at %d < target %d",
                        cp.family(), cp.partitionId(), cp.position(), targetPos));
        return false;
    }

    private static Long resolveTargetPosition(Map<String, Long> targetPrefix, String family) {
        Long targetPos = targetPrefix.get(toWriteFamily(family));
        return targetPos != null ? targetPos : targetPrefix.get(family);
    }

    private SlotEvaluation evaluateSlots(Map<String, Long> targetPrefix, List<SlotEntry> slots) {
        int residualSlots = 0;
        int quarantinedSlots = 0;
        List<String> diagnostics = new ArrayList<>();
        for (SlotEntry slot : slots) {
            if ("QUARANTINED".equalsIgnoreCase(slot.state())) {
                quarantinedSlots++;
            }
            if (isResidualSlot(targetPrefix, slot, diagnostics)) {
                residualSlots++;
            }
        }
        boolean satisfied = residualSlots == 0 && quarantinedSlots == 0;
        return new SlotEvaluation(
                satisfied, residualSlots, quarantinedSlots, String.join(", ", diagnostics));
    }

    private static boolean isResidualSlot(
            Map<String, Long> targetPrefix, SlotEntry slot, List<String> diagnostics) {
        if (slot.position() < 0) {
            diagnostics.add(
                    String.format("Slot %s position UNRESOLVED in WRITE", slot.consumableId()));
            return true;
        }
        Long targetPos = resolveTargetPosition(targetPrefix, slot.family());
        if (targetPos == null) {
            diagnostics.add(
                    String.format(
                            "Slot %s family '%s' missing from targetPrefix",
                            slot.consumableId(), slot.family()));
            return true;
        }
        if (slot.position() <= targetPos) {
            diagnostics.add(
                    String.format(
                            "Slot %s (state=%s) at pos %d <= target %d",
                            slot.consumableId(), slot.state(), slot.position(), targetPos));
            return true;
        }
        return false;
    }

    private static String buildEvaluationDiagnostic(
            CheckpointEvaluation cpEval,
            SlotEvaluation slotEval,
            List<String> integrityErrors) {
        StringBuilder sb = new StringBuilder();
        if (!cpEval.satisfied()) {
            sb.append("Checkpoints incomplete (")
                    .append(cpEval.count())
                    .append("/")
                    .append(EXPECTED_CHECKPOINTS_COUNT)
                    .append("): ")
                    .append(cpEval.diagnostic())
                    .append("; ");
        }
        if (!slotEval.satisfied()) {
            sb.append("Residual slots <= target: ")
                    .append(slotEval.residual())
                    .append(" (quarantined: ")
                    .append(slotEval.quarantined())
                    .append("): ")
                    .append(slotEval.diagnostic())
                    .append("; ");
        }
        if (!integrityErrors.isEmpty()) {
            sb.append("Integrity violations: ").append(String.join(", ", integrityErrors));
        }
        return sb.isEmpty() ? "READY predicate fully satisfied" : sb.toString().trim();
    }

    /**
     * Database evaluation method querying PostgreSQL directly.
     * Uses batch queries to eliminate per-slot latency.
     */
    public Evaluation evaluate(
            JdbcTemplate readJdbc,
            JdbcTemplate writeJdbc,
            Map<String, Long> targetPrefix) {
        List<CheckpointEntry> checkpoints = fetchCheckpoints(readJdbc);
        List<SlotEntry> slots = fetchSlots(readJdbc, writeJdbc);
        List<String> integrityErrors = checkRelationalIntegrity(readJdbc);
        return evaluatePure(targetPrefix, checkpoints, slots, integrityErrors);
    }

    private static List<CheckpointEntry> fetchCheckpoints(JdbcTemplate readJdbc) {
        String cpSql =
                "SELECT dc.consumption_id, crc.family, dc.partition_id, dc.position "
                        + "FROM discovery_checkpoint dc "
                        + "JOIN consumption_routing_contract crc ON dc.consumption_id ="
                        + " crc.consumption_id "
                        + "WHERE crc.partition_count = 4";
        return readJdbc.query(
                cpSql,
                (rs, rowNum) ->
                        new CheckpointEntry(
                                (UUID) rs.getObject("consumption_id"),
                                rs.getString("family"),
                                rs.getInt("partition_id"),
                                rs.getLong("position")));
    }

    private static List<SlotEntry> fetchSlots(JdbcTemplate readJdbc, JdbcTemplate writeJdbc) {
        String slotSql =
                "SELECT cs.consumption_id, crc.family, cs.consumable_id, cs.state "
                        + "FROM consumption_slot cs "
                        + "JOIN consumption_routing_contract crc ON cs.consumption_id ="
                        + " crc.consumption_id";
        List<SlotRaw> rawSlots =
                readJdbc.query(
                        slotSql,
                        (rs, rowNum) ->
                                new SlotRaw(
                                        (UUID) rs.getObject("consumption_id"),
                                        rs.getString("family"),
                                        (UUID) rs.getObject("consumable_id"),
                                        rs.getString("state")));
        if (rawSlots.isEmpty()) return List.of();
        Map<UUID, Long> positionMap = resolveWritePositions(writeJdbc, rawSlots);
        return rawSlots.stream()
                .map(
                        raw -> {
                            Long pos = positionMap.get(raw.consumableId);
                            long resolvedPos = pos != null ? pos : -1L;
                            return new SlotEntry(
                                    raw.consumptionId,
                                    raw.family,
                                    raw.consumableId,
                                    resolvedPos,
                                    raw.state);
                        })
                .toList();
    }

    private static Map<UUID, Long> resolveWritePositions(
            JdbcTemplate writeJdbc, List<SlotRaw> rawSlots) {
        Map<UUID, Long> positionMap = new HashMap<>();
        List<UUID> ids = rawSlots.stream().map(s -> s.consumableId).distinct().toList();
        int chunkSize = 500;
        for (int i = 0; i < ids.size(); i += chunkSize) {
            List<UUID> chunk = ids.subList(i, Math.min(i + chunkSize, ids.size()));
            String inClause = String.join(",", Collections.nCopies(chunk.size(), "?"));
            String batchSql =
                    "SELECT event_id, position FROM v3_event_position WHERE event_id IN ("
                            + inClause
                            + ")";
            writeJdbc.query(
                    batchSql,
                    (rs, rowNum) -> {
                        positionMap.put((UUID) rs.getObject("event_id"), rs.getLong("position"));
                        return null;
                    },
                    chunk.toArray());
        }
        return positionMap;
    }

    private static List<String> checkRelationalIntegrity(JdbcTemplate readJdbc) {
        List<String> integrityErrors = new ArrayList<>();
        addIfPositive(
                readJdbc,
                "SELECT count(*) FROM read_current_pot cp "
                        + "LEFT JOIN read_pot_version pv ON cp.pot_id = pv.pot_id AND"
                        + " cp.pot_version = pv.pot_version "
                        + "WHERE pv.pot_id IS NULL",
                "read_current_pot has %d dangling version references",
                integrityErrors);
        addIfPositive(
                readJdbc,
                """
                select count(*) from read_pot_version pv
                left join read_pot_balance pb using (pot_id, pot_version)
                where (not pv.deleted and pb.pot_id is null)
                   or (pv.deleted and pb.pot_id is not null)
                """,
                "%d Pot versions with missing balance rows or unexpected terminal balances",
                integrityErrors);
        addIfPositive(
                readJdbc,
                """
                select count(*) from read_pot_balance pb
                left join read_pot_balance_content bc on bc.content_hash = pb.content_hash
                where bc.content_hash is null
                """,
                "read_pot_balance has %d dangling content_hash references",
                integrityErrors);
        addIfPositive(
                readJdbc,
                """
                select count(*) from read_pot_balance_aggregate pba
                left join read_pot_balance_aggregate_content bac on bac.content_hash = pba.content_hash
                where bac.content_hash is null
                """,
                "read_pot_balance_aggregate has %d dangling content_hash references",
                integrityErrors);
        addIfPositive(
                readJdbc,
                """
                select count(*) from read_pot_balance_content bc
                left join read_pot_shareholder_set ss on ss.set_hash = bc.shareholder_set_hash
                where ss.set_hash is null or ss.shareholders_count <> bc.shareholders_count
                """,
                "read_pot_balance_content has %d inconsistent shareholder_set_hash references",
                integrityErrors);
        addIfPositive(
                readJdbc,
                """
                select count(*) from read_pot_balance_aggregate_content bac
                left join read_pot_shareholder_set ss on ss.set_hash = bac.shareholder_set_hash
                where ss.set_hash is null or ss.shareholders_count <> bac.shareholders_count
                """,
                "read_pot_balance_aggregate_content has %d inconsistent shareholder_set_hash references",
                integrityErrors);
        addIfPositive(
                readJdbc,
                """
                select count(*) from read_pot_version pv
                left join read_pot_version_node sh on sh.node_hash = pv.shareholders_root
                left join read_pot_version_node ex on ex.node_hash = pv.expenses_root
                where (pv.shareholders_root is not null
                       and (sh.node_hash is null or sh.tree_kind <> 'S' or sh.entry_count <> pv.shareholders_count))
                   or (pv.expenses_root is not null
                       and (ex.node_hash is null or ex.tree_kind <> 'E' or ex.entry_count <> pv.expenses_count))
                   or (pv.authorized_users_hash is not null
                       and not exists (select 1 from read_pot_user_set u where u.set_hash = pv.authorized_users_hash))
                   or (pv.attached_active_users_hash is not null
                       and not exists (select 1 from read_pot_user_set u where u.set_hash = pv.attached_active_users_hash))
                """,
                "%d Pot versions with inconsistent Radix-16 roots or user sets",
                integrityErrors);
        addIfPositive(
                readJdbc,
                "SELECT count(*) FROM read_current_pot cp "
                        + "LEFT JOIN read_pot_visibility_state vs ON cp.pot_id = vs.pot_id AND"
                        + " cp.pot_version <= vs.materialized_version "
                        + "WHERE vs.pot_id IS NULL",
                "read_current_pot has %d missing visibility rows",
                integrityErrors);
        addIfPositive(
                readJdbc,
                "SELECT count(*) FROM read_current_binding WHERE eui_version < 0",
                "read_current_binding has %d invalid versions",
                integrityErrors);
        return integrityErrors;
    }

    private static void addIfPositive(
            JdbcTemplate readJdbc, String sql, String messageFormat, List<String> errors) {
        Integer count = readJdbc.queryForObject(sql, Integer.class);
        if (count != null && count > 0) {
            errors.add(String.format(messageFormat, count));
        }
    }

    private record SlotRaw(UUID consumptionId, String family, UUID consumableId, String state) {}
}
