package com.kartaguez.pocoma.compose.polling.materialize.v3.maintenance;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition;
import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionCatalog;
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
        if (!family.endsWith("_EVENT") && !family.equals("COMMAND") && !family.equals("IDENTITY_REQUEST")) {
            return family + "_EVENT";
        }
        return family;
    }

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

        List<String> checkpointDiagnostics = new ArrayList<>();

        // Conservative pre-check: All canonical WRITE families must be present in targetPrefix B
        Set<String> missingWriteFamilies = new HashSet<>();
        for (String expectedFam : EXPECTED_WRITE_FAMILIES) {
            if (!targetPrefix.containsKey(expectedFam)) {
                missingWriteFamilies.add(expectedFam);
            }
        }
        if (!missingWriteFamilies.isEmpty()) {
            checkpointDiagnostics.add("Target prefix B missing expected families: " + missingWriteFamilies);
        }

        // 1. Condition C_checkpoints
        int satisfiedCheckpoints = 0;
        Set<CheckpointKey> seenKeys = new HashSet<>();
        boolean catalogMismatch = false;

        for (CheckpointEntry cp : checkpoints) {
            CheckpointKey key = new CheckpointKey(cp.consumptionId(), cp.partitionId());
            if (!EXPECTED_CHECKPOINT_KEYS.contains(key)) {
                checkpointDiagnostics.add(String.format("Unexpected checkpoint: %s p%d", cp.consumptionId(), cp.partitionId()));
                catalogMismatch = true;
                continue;
            }
            if (!seenKeys.add(key)) {
                checkpointDiagnostics.add(String.format("Duplicate checkpoint: %s p%d", cp.consumptionId(), cp.partitionId()));
                catalogMismatch = true;
                continue;
            }

            String writeFam = toWriteFamily(cp.family());
            Long targetPos = targetPrefix.get(writeFam);
            if (targetPos == null) {
                targetPos = targetPrefix.get(cp.family());
            }

            if (targetPos == null) {
                checkpointDiagnostics.add(String.format("Checkpoint %s p%d family '%s' not found in target prefix",
                        cp.consumptionId(), cp.partitionId(), cp.family()));
            } else if (cp.position() >= targetPos) {
                satisfiedCheckpoints++;
            } else {
                checkpointDiagnostics.add(String.format("Checkpoint %s p%d at %d < target %d",
                        cp.family(), cp.partitionId(), cp.position(), targetPos));
            }
        }

        // Check if all expected keys are present
        if (seenKeys.size() < EXPECTED_CHECKPOINT_KEYS.size()) {
            Set<CheckpointKey> missing = new HashSet<>(EXPECTED_CHECKPOINT_KEYS);
            missing.removeAll(seenKeys);
            checkpointDiagnostics.add("Missing checkpoints: " + missing.size());
        }

        boolean checkpointsSatisfied = missingWriteFamilies.isEmpty()
                && !catalogMismatch
                && (seenKeys.size() == EXPECTED_CHECKPOINTS_COUNT)
                && (satisfiedCheckpoints == EXPECTED_CHECKPOINTS_COUNT);

        // 2. Condition C_slots: Strictly 0 slots with position <= targetPrefix, and 0 unresolved positions
        int residualSlots = 0;
        int quarantinedSlots = 0;
        List<String> slotDiagnostics = new ArrayList<>();

        for (SlotEntry slot : slots) {
            if ("QUARANTINED".equalsIgnoreCase(slot.state())) {
                quarantinedSlots++;
            }

            // Negative position indicates position could not be resolved from WRITE
            if (slot.position() < 0) {
                residualSlots++;
                slotDiagnostics.add(String.format("Slot %s position UNRESOLVED in WRITE", slot.consumableId()));
                continue;
            }

            String writeFam = toWriteFamily(slot.family());
            Long targetPos = targetPrefix.get(writeFam);
            if (targetPos == null) {
                targetPos = targetPrefix.get(slot.family());
            }

            if (targetPos == null) {
                residualSlots++;
                slotDiagnostics.add(String.format("Slot %s family '%s' missing from targetPrefix",
                        slot.consumableId(), slot.family()));
            } else if (slot.position() <= targetPos) {
                residualSlots++;
                slotDiagnostics.add(String.format("Slot %s (state=%s) at pos %d <= target %d",
                        slot.consumableId(), slot.state(), slot.position(), targetPos));
            }
        }

        boolean slotsSatisfied = (residualSlots == 0) && (quarantinedSlots == 0);

        // 3. Condition C_integrity
        boolean integritySatisfied = integrityErrors.isEmpty();

        boolean ready = checkpointsSatisfied && slotsSatisfied && integritySatisfied;

        StringBuilder sb = new StringBuilder();
        if (!checkpointsSatisfied) {
            sb.append("Checkpoints incomplete (").append(satisfiedCheckpoints).append("/")
              .append(EXPECTED_CHECKPOINTS_COUNT).append("): ").append(String.join(", ", checkpointDiagnostics)).append("; ");
        }
        if (!slotsSatisfied) {
            sb.append("Residual slots <= target: ").append(residualSlots)
              .append(" (quarantined: ").append(quarantinedSlots).append("): ")
              .append(String.join(", ", slotDiagnostics)).append("; ");
        }
        if (!integritySatisfied) {
            sb.append("Integrity violations: ").append(String.join(", ", integrityErrors));
        }

        String diagnostic = sb.isEmpty() ? "READY predicate fully satisfied" : sb.toString().trim();

        return new Evaluation(
                ready,
                checkpointsSatisfied,
                satisfiedCheckpoints,
                EXPECTED_CHECKPOINTS_COUNT,
                slotsSatisfied,
                residualSlots,
                quarantinedSlots,
                integritySatisfied,
                diagnostic);
    }

    /**
     * Database evaluation method querying PostgreSQL directly.
     * Uses batch queries to eliminate per-slot latency.
     */
    public Evaluation evaluate(
            JdbcTemplate readJdbc,
            JdbcTemplate writeJdbc,
            Map<String, Long> targetPrefix) {

        // 1. Fetch checkpoints
        String cpSql = "SELECT dc.consumption_id, crc.family, dc.partition_id, dc.position "
                + "FROM discovery_checkpoint dc "
                + "JOIN consumption_routing_contract crc ON dc.consumption_id = crc.consumption_id "
                + "WHERE crc.partition_count = 4";
        List<CheckpointEntry> checkpoints = readJdbc.query(cpSql, (rs, rowNum) -> new CheckpointEntry(
                (UUID) rs.getObject("consumption_id"),
                rs.getString("family"),
                rs.getInt("partition_id"),
                rs.getLong("position")
        ));

        // 2. Fetch slots and cross-reference position with writeJdbc via single batch query
        String slotSql = "SELECT cs.consumption_id, crc.family, cs.consumable_id, cs.state "
                + "FROM consumption_slot cs "
                + "JOIN consumption_routing_contract crc ON cs.consumption_id = crc.consumption_id";
        List<SlotRaw> rawSlots = readJdbc.query(slotSql, (rs, rowNum) -> new SlotRaw(
                (UUID) rs.getObject("consumption_id"),
                rs.getString("family"),
                (UUID) rs.getObject("consumable_id"),
                rs.getString("state")
        ));

        List<SlotEntry> slots = new ArrayList<>();
        if (!rawSlots.isEmpty()) {
            // Batch resolve positions from v3_event_position in WRITE
            Map<UUID, Long> positionMap = new HashMap<>();
            List<UUID> ids = rawSlots.stream().map(s -> s.consumableId).distinct().toList();

            // Query in chunks of 500 if large
            int chunkSize = 500;
            for (int i = 0; i < ids.size(); i += chunkSize) {
                List<UUID> chunk = ids.subList(i, Math.min(i + chunkSize, ids.size()));
                String inClause = String.join(",", Collections.nCopies(chunk.size(), "?"));
                String batchSql = "SELECT event_id, position FROM v3_event_position WHERE event_id IN (" + inClause + ")";
                writeJdbc.query(batchSql, (rs, rowNum) -> {
                    positionMap.put((UUID) rs.getObject("event_id"), rs.getLong("position"));
                    return null;
                }, chunk.toArray());
            }

            for (SlotRaw raw : rawSlots) {
                Long pos = positionMap.get(raw.consumableId);
                // Unresolved position marked as -1L so evaluatePure strictly treats as residual
                long resolvedPos = pos != null ? pos : -1L;
                slots.add(new SlotEntry(raw.consumptionId, raw.family, raw.consumableId, resolvedPos, raw.state));
            }
        }

        // 3. Relational integrity checks
        List<String> integrityErrors = new ArrayList<>();

        // Check 3.1: CurrentPot -> PotVersion
        Integer danglingCurrentPot = readJdbc.queryForObject(
                "SELECT count(*) FROM read_current_pot cp "
                        + "LEFT JOIN read_pot_version pv ON cp.pot_id = pv.pot_id AND cp.pot_version = pv.pot_version "
                        + "WHERE pv.pot_id IS NULL",
                Integer.class
        );
        if (danglingCurrentPot != null && danglingCurrentPot > 0) {
            integrityErrors.add("read_current_pot has " + danglingCurrentPot + " dangling version references");
        }

        // Active historical versions keep their balances. A terminal DeletePot version has none.
        Integer invalidBalances = readJdbc.queryForObject(
                """
                select count(*) from read_pot_version pv
                left join read_pot_balance pb using (pot_id, pot_version)
                where jsonb_typeof(pv.payload->'deleted') is distinct from 'boolean'
                   or (pv.payload->'deleted' = 'false'::jsonb and pb.pot_id is null)
                   or (pv.payload->'deleted' = 'true'::jsonb and pb.pot_id is not null)
                """,
                Integer.class
        );
        if (invalidBalances != null && invalidBalances > 0) {
            integrityErrors.add(invalidBalances + " Pot versions with missing balance rows, unexpected terminal balances or invalid deletion flags");
        }

        // Check 3.3: CurrentPot -> PotVisibility
        Integer missingVisibility = readJdbc.queryForObject(
                "SELECT count(*) FROM read_current_pot cp "
                        + "LEFT JOIN read_pot_visibility_state vs ON cp.pot_id = vs.pot_id AND cp.pot_version <= vs.materialized_version "
                        + "WHERE vs.pot_id IS NULL",
                Integer.class
        );
        if (missingVisibility != null && missingVisibility > 0) {
            integrityErrors.add("read_current_pot has " + missingVisibility + " missing visibility rows");
        }

        // Check 3.4: CurrentBinding sanity
        Integer invalidBindings = readJdbc.queryForObject(
                "SELECT count(*) FROM read_current_binding WHERE eui_version < 0",
                Integer.class
        );
        if (invalidBindings != null && invalidBindings > 0) {
            integrityErrors.add("read_current_binding has " + invalidBindings + " invalid versions");
        }

        return evaluatePure(targetPrefix, checkpoints, slots, integrityErrors);
    }

    private record SlotRaw(UUID consumptionId, String family, UUID consumableId, String state) {}
}
