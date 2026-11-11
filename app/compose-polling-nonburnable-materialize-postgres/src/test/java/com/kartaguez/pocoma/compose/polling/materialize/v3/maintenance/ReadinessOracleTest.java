package com.kartaguez.pocoma.compose.polling.materialize.v3.maintenance;
import com.kartaguez.pocoma.compose.polling.materialize.v3.maintenance.ReadinessOracle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ReadinessOracleTest {

    private ReadinessOracle oracle;
    private final Map<String, UUID> canonicalConsumptions = new LinkedHashMap<>();
    private final Map<String, String> consumptionFamilies = new LinkedHashMap<>();

    private final Map<String, Long> standardTargetPrefix = Map.of(
            "POT", 100L,
            "IDENTITY", 50L,
            "COMMAND_COMPLETION", 20L,
            "IDENTITY_COMPLETION", 10L,
            "INVITATION", 5L
    );

    @BeforeEach
    void setUp() {
        oracle = new ReadinessOracle();

        canonicalConsumptions.put("MaterializePotVersion", UUID.fromString("3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a"));
        consumptionFamilies.put("MaterializePotVersion", "POT_EVENT");

        canonicalConsumptions.put("MaterializePotBalance", UUID.fromString("190a93c6-b8af-5ac7-8001-72e2ec53c2df"));
        consumptionFamilies.put("MaterializePotBalance", "POT_EVENT");

        canonicalConsumptions.put("MaterializePotVisibility", UUID.fromString("b4d04cce-b5da-5d1b-9698-5ff9af9a46fa"));
        consumptionFamilies.put("MaterializePotVisibility", "POT_EVENT");

        canonicalConsumptions.put("CurrentBinding", UUID.fromString("7b8d2838-bf7a-5117-868a-e5c14b4c0221"));
        consumptionFamilies.put("CurrentBinding", "IDENTITY_EVENT");

        canonicalConsumptions.put("CommandResult", UUID.fromString("63664303-d33e-5d42-8cce-c4dae8023311"));
        consumptionFamilies.put("CommandResult", "COMMAND_COMPLETION_EVENT");

        canonicalConsumptions.put("IdentityRequestResult", UUID.fromString("1d8ff246-358f-5ddd-a7f5-416efcfd7445"));
        consumptionFamilies.put("IdentityRequestResult", "IDENTITY_COMPLETION_EVENT");

        canonicalConsumptions.put("Invitation", UUID.fromString("924cd9e3-435d-5804-9d1e-c6aecadb9ab4"));
        consumptionFamilies.put("Invitation", "INVITATION_EVENT");
    }

    private List<ReadinessOracle.CheckpointEntry> generate28Checkpoints(long position) {
        List<ReadinessOracle.CheckpointEntry> list = new ArrayList<>();
        for (var entry : canonicalConsumptions.entrySet()) {
            UUID cid = entry.getValue();
            String family = consumptionFamilies.get(entry.getKey());
            for (int p = 0; p < 4; p++) {
                list.add(new ReadinessOracle.CheckpointEntry(cid, family, p, position));
            }
        }
        return list;
    }

    @Test
    void whenAllCheckpointsCompleteAndNoSlotsAndNoIntegrityErrors_returnsReady() {
        List<ReadinessOracle.CheckpointEntry> checkpoints = new ArrayList<>();
        for (var entry : canonicalConsumptions.entrySet()) {
            UUID cid = entry.getValue();
            String family = consumptionFamilies.get(entry.getKey());
            String writeFam = ReadinessOracle.toWriteFamily(family);
            long pos = standardTargetPrefix.get(writeFam) + 5; // Beyond target
            for (int p = 0; p < 4; p++) {
                checkpoints.add(new ReadinessOracle.CheckpointEntry(cid, family, p, pos));
            }
        }

        var eval = oracle.evaluatePure(standardTargetPrefix, checkpoints, List.of(), List.of());

        assertTrue(eval.ready(), "Must be READY when all 3 conditions are satisfied");
        assertTrue(eval.checkpointsSatisfied());
        assertEquals(28, eval.satisfiedCheckpointsCount());
        assertTrue(eval.slotsSatisfied());
        assertEquals(0, eval.residualSlotsCount());
        assertTrue(eval.integritySatisfied());
    }

    @Test
    void whenEvenOneCheckpointIsBelowTarget_readyIsRefused() {
        List<ReadinessOracle.CheckpointEntry> checkpoints = new ArrayList<>();
        for (var entry : canonicalConsumptions.entrySet()) {
            UUID cid = entry.getValue();
            String family = consumptionFamilies.get(entry.getKey());
            String writeFam = ReadinessOracle.toWriteFamily(family);
            long pos = standardTargetPrefix.get(writeFam);
            for (int p = 0; p < 4; p++) {
                // One partition lags behind: partition 3 of PotBalance at 99 instead of 100
                if (entry.getKey().equals("MaterializePotBalance") && p == 3) {
                    checkpoints.add(new ReadinessOracle.CheckpointEntry(cid, family, p, 99L));
                } else {
                    checkpoints.add(new ReadinessOracle.CheckpointEntry(cid, family, p, pos));
                }
            }
        }

        var eval = oracle.evaluatePure(standardTargetPrefix, checkpoints, List.of(), List.of());

        assertFalse(eval.ready());
        assertFalse(eval.checkpointsSatisfied());
        assertEquals(27, eval.satisfiedCheckpointsCount());
        assertTrue(eval.diagnostic().contains("p3 at 99 < target 100"));
    }

    @Test
    void whenCheckpointIsMissing_readyIsRefused() {
        List<ReadinessOracle.CheckpointEntry> checkpoints = generate28Checkpoints(100L);
        checkpoints.removeLast(); // Only 27 checkpoints

        var eval = oracle.evaluatePure(standardTargetPrefix, checkpoints, List.of(), List.of());

        assertFalse(eval.ready());
        assertFalse(eval.checkpointsSatisfied());
        assertEquals(27, eval.satisfiedCheckpointsCount());
        assertTrue(eval.diagnostic().contains("Missing checkpoints: 1"));
    }

    @Test
    void whenTargetPrefixMissingExpectedFamily_readyIsRefused() {
        // Missing INVITATION, IDENTITY, COMMAND_COMPLETION, IDENTITY_COMPLETION
        Map<String, Long> incompleteTarget = Map.of("POT", 100L);
        List<ReadinessOracle.CheckpointEntry> checkpoints = generate28Checkpoints(200L);

        var eval = oracle.evaluatePure(incompleteTarget, checkpoints, List.of(), List.of());

        assertFalse(eval.ready(), "Target prefix missing required write families must block READY");
        assertFalse(eval.checkpointsSatisfied());
        assertTrue(eval.diagnostic().contains("missing expected families"));
    }

    @Test
    void whenUnexpectedCheckpointAppears_readyIsRefused() {
        List<ReadinessOracle.CheckpointEntry> checkpoints = generate28Checkpoints(200L);
        // Add unexpected checkpoint with unknown consumption ID
        checkpoints.add(new ReadinessOracle.CheckpointEntry(UUID.randomUUID(), "POT_EVENT", 0, 200L));

        var eval = oracle.evaluatePure(standardTargetPrefix, checkpoints, List.of(), List.of());

        assertFalse(eval.ready(), "Unknown/unexpected checkpoint must block READY");
        assertFalse(eval.checkpointsSatisfied());
        assertTrue(eval.diagnostic().contains("Unexpected checkpoint"));
    }

    @Test
    void whenDuplicateCheckpointAppears_readyIsRefused() {
        List<ReadinessOracle.CheckpointEntry> checkpoints = generate28Checkpoints(200L);
        // Duplicate the first checkpoint
        checkpoints.add(checkpoints.getFirst());

        var eval = oracle.evaluatePure(standardTargetPrefix, checkpoints, List.of(), List.of());

        assertFalse(eval.ready(), "Duplicate checkpoint must block READY");
        assertFalse(eval.checkpointsSatisfied());
        assertTrue(eval.diagnostic().contains("Duplicate checkpoint"));
    }

    @Test
    void whenSlotPositionUnresolvedInWrite_readyIsRefused() {
        List<ReadinessOracle.CheckpointEntry> checkpoints = generate28Checkpoints(200L);

        // Slot with unresolved position (-1L)
        List<ReadinessOracle.SlotEntry> slots = List.of(
                new ReadinessOracle.SlotEntry(
                        canonicalConsumptions.get("MaterializePotVersion"),
                        "POT_EVENT",
                        UUID.randomUUID(),
                        -1L,
                        "AVAILABLE"
                )
        );

        var eval = oracle.evaluatePure(standardTargetPrefix, checkpoints, slots, List.of());

        assertFalse(eval.ready(), "Unresolved slot position in WRITE must block READY");
        assertFalse(eval.slotsSatisfied());
        assertEquals(1, eval.residualSlotsCount());
        assertTrue(eval.diagnostic().contains("UNRESOLVED in WRITE"));
    }

    @Test
    void whenSlotExistsBelowTarget_readyIsRefused() {
        List<ReadinessOracle.CheckpointEntry> checkpoints = generate28Checkpoints(200L);

        // One slot at position 80 <= 100 in AVAILABLE state
        List<ReadinessOracle.SlotEntry> slots = List.of(
                new ReadinessOracle.SlotEntry(
                        canonicalConsumptions.get("MaterializePotVersion"),
                        "POT_EVENT",
                        UUID.randomUUID(),
                        80L,
                        "AVAILABLE"
                )
        );

        var eval = oracle.evaluatePure(standardTargetPrefix, checkpoints, slots, List.of());

        assertFalse(eval.ready());
        assertTrue(eval.checkpointsSatisfied());
        assertFalse(eval.slotsSatisfied());
        assertEquals(1, eval.residualSlotsCount());
        assertEquals(0, eval.quarantinedSlotsCount());
    }

    @Test
    void whenSlotIsQuarantinedBelowTarget_readyIsRefused() {
        List<ReadinessOracle.CheckpointEntry> checkpoints = generate28Checkpoints(200L);

        // One quarantined slot at position 50 <= 100
        List<ReadinessOracle.SlotEntry> slots = List.of(
                new ReadinessOracle.SlotEntry(
                        canonicalConsumptions.get("MaterializePotBalance"),
                        "POT_EVENT",
                        UUID.randomUUID(),
                        50L,
                        "QUARANTINED"
                )
        );

        var eval = oracle.evaluatePure(standardTargetPrefix, checkpoints, slots, List.of());

        assertFalse(eval.ready());
        assertFalse(eval.slotsSatisfied());
        assertEquals(1, eval.residualSlotsCount());
        assertEquals(1, eval.quarantinedSlotsCount());
        assertTrue(eval.diagnostic().contains("quarantined: 1"));
    }

    @Test
    void whenSlotExistsAboveTarget_doesNotBlockReady() {
        List<ReadinessOracle.CheckpointEntry> checkpoints = generate28Checkpoints(200L);

        // One slot at position 105 > 100 (new WRITE event during replay)
        List<ReadinessOracle.SlotEntry> slots = List.of(
                new ReadinessOracle.SlotEntry(
                        canonicalConsumptions.get("MaterializePotVersion"),
                        "POT_EVENT",
                        UUID.randomUUID(),
                        105L,
                        "AVAILABLE"
                )
        );

        var eval = oracle.evaluatePure(standardTargetPrefix, checkpoints, slots, List.of());

        assertTrue(eval.ready(), "Events > B must not block READY");
        assertTrue(eval.slotsSatisfied());
        assertEquals(0, eval.residualSlotsCount());
    }

    @Test
    void whenRelationalIntegrityViolated_readyIsRefused() {
        List<ReadinessOracle.CheckpointEntry> checkpoints = generate28Checkpoints(100L);

        List<String> integrityViolations = List.of("read_current_pot has 2 dangling version references");

        var eval = oracle.evaluatePure(standardTargetPrefix, checkpoints, List.of(), integrityViolations);

        assertFalse(eval.ready());
        assertTrue(eval.checkpointsSatisfied());
        assertTrue(eval.slotsSatisfied());
        assertFalse(eval.integritySatisfied());
        assertTrue(eval.diagnostic().contains("dangling version references"));
    }

    @Test
    void familyMapping_supportsBothCanonicalAndShorthandNames() {
        assertEquals("POT", ReadinessOracle.toWriteFamily("POT_EVENT"));
        assertEquals("INVITATION", ReadinessOracle.toWriteFamily("INVITATION_EVENT"));
        assertEquals("IDENTITY", ReadinessOracle.toWriteFamily("IDENTITY_EVENT"));
        assertEquals("COMMAND_COMPLETION", ReadinessOracle.toWriteFamily("COMMAND_COMPLETION_EVENT"));
        assertEquals("IDENTITY_COMPLETION", ReadinessOracle.toWriteFamily("IDENTITY_COMPLETION_EVENT"));

        assertEquals("POT_EVENT", ReadinessOracle.toReadFamily("POT"));
        assertEquals("INVITATION_EVENT", ReadinessOracle.toReadFamily("INVITATION"));
        assertEquals("IDENTITY_EVENT", ReadinessOracle.toReadFamily("IDENTITY"));
        assertEquals("COMMAND_COMPLETION_EVENT", ReadinessOracle.toReadFamily("COMMAND_COMPLETION"));
        assertEquals("IDENTITY_COMPLETION_EVENT", ReadinessOracle.toReadFamily("IDENTITY_COMPLETION"));
    }

    @Test
    void emptyInitialState_withZeroPrefix_convergesToReady() {
        Map<String, Long> targetPrefix = Map.of(
                "POT", 0L,
                "IDENTITY", 0L,
                "COMMAND_COMPLETION", 0L,
                "IDENTITY_COMPLETION", 0L,
                "INVITATION", 0L
        );
        List<ReadinessOracle.CheckpointEntry> checkpoints = generate28Checkpoints(0L);

        var eval = oracle.evaluatePure(targetPrefix, checkpoints, List.of(), List.of());

        assertTrue(eval.ready());
        assertTrue(eval.checkpointsSatisfied());
        assertEquals(28, eval.satisfiedCheckpointsCount());
    }
}
