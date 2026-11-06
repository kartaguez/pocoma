package com.kartaguez.pocoma.runtime.consumption.v3.maintenance;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/**
 * Evaluates the mathematical readiness predicate P_READY(B) = C_checkpoints & C_slots & C_integrity.
 */
public final class ReadinessOracle {

    public static final int EXPECTED_CHECKPOINTS_COUNT = 28; // 7 consumptions x 4 partitions

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
     * In-memory evaluation method for unit testing and fast contract verification.
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

        // 1. Condition C_checkpoints
        int satisfiedCheckpoints = 0;
        Set<String> seenCheckpointKeys = new HashSet<>();
        List<String> checkpointDiagnostics = new ArrayList<>();

        for (CheckpointEntry cp : checkpoints) {
            String writeFam = toWriteFamily(cp.family());
            Long targetPos = targetPrefix.get(writeFam);
            if (targetPos == null) {
                targetPos = targetPrefix.get(cp.family());
            }
            if (targetPos == null) {
                targetPos = 0L;
            }

            String key = cp.consumptionId() + ":" + cp.partitionId();
            if (seenCheckpointKeys.add(key)) {
                if (cp.position() >= targetPos) {
                    satisfiedCheckpoints++;
                } else {
                    checkpointDiagnostics.add(String.format("Checkpoint %s p%d at %d < target %d",
                            cp.family(), cp.partitionId(), cp.position(), targetPos));
                }
            }
        }

        boolean checkpointsSatisfied = (satisfiedCheckpoints == EXPECTED_CHECKPOINTS_COUNT)
                && (checkpoints.size() >= EXPECTED_CHECKPOINTS_COUNT);

        // 2. Condition C_slots: Strictly 0 slots with position <= targetPrefix
        int residualSlots = 0;
        int quarantinedSlots = 0;
        List<String> slotDiagnostics = new ArrayList<>();

        for (SlotEntry slot : slots) {
            String writeFam = toWriteFamily(slot.family());
            Long targetPos = targetPrefix.get(writeFam);
            if (targetPos == null) {
                targetPos = targetPrefix.get(slot.family());
            }
            if (targetPos == null) {
                targetPos = 0L;
            }

            if (slot.position() <= targetPos) {
                residualSlots++;
                if ("QUARANTINED".equalsIgnoreCase(slot.state())) {
                    quarantinedSlots++;
                }
                slotDiagnostics.add(String.format("Slot %s (state=%s) at pos %d <= target %d",
                        slot.consumableId(), slot.state(), slot.position(), targetPos));
            }
        }

        boolean slotsSatisfied = (residualSlots == 0);

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

        // 2. Fetch slots and cross-reference position with writeJdbc
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
        for (SlotRaw raw : rawSlots) {
            String writeFam = toWriteFamily(raw.family);
            // Query position from v3_event_position in WRITE
            List<Long> posList = writeJdbc.query(
                    "SELECT position FROM v3_event_position WHERE event_id = ? AND family = ?",
                    (rs, rowNum) -> rs.getLong("position"),
                    raw.consumableId, writeFam
            );
            long pos = posList.isEmpty() ? Long.MAX_VALUE : posList.getFirst();
            slots.add(new SlotEntry(raw.consumptionId, raw.family, raw.consumableId, pos, raw.state));
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

        // Check 3.2: CurrentPot -> PotBalance
        Integer missingBalance = readJdbc.queryForObject(
                "SELECT count(*) FROM read_current_pot cp "
                        + "LEFT JOIN read_pot_balance pb ON cp.pot_id = pb.pot_id AND cp.pot_version = pb.pot_version "
                        + "WHERE pb.pot_id IS NULL",
                Integer.class
        );
        if (missingBalance != null && missingBalance > 0) {
            integrityErrors.add("read_current_pot has " + missingBalance + " missing balance rows");
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
