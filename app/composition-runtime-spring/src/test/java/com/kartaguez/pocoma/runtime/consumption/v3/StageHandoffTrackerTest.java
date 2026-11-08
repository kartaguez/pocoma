package com.kartaguez.pocoma.runtime.consumption.v3;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId;
import com.kartaguez.pocoma.engine.consumption.v3.ConsumptionEngine;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StageHandoffTrackerTest {

    private StageHandoffTracker tracker;
    private SimpleMeterRegistry registry;

    private static final ConsumptionId COMMAND_ID =
            new ConsumptionId(UUID.fromString("98f7268d-d340-581c-b83d-010926223e14"));
    private static final ConsumptionId POT_VERSION_ID =
            new ConsumptionId(UUID.fromString("3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a"));
    private static final ConsumptionId POT_BALANCE_ID =
            new ConsumptionId(UUID.fromString("190a93c6-b8af-5ac7-8001-72e2ec53c2df"));
    private static final ConsumptionId COMMAND_RESULT_ID =
            new ConsumptionId(UUID.fromString("63664303-d33e-5d42-8cce-c4dae8023311"));

    @BeforeEach
    void setUp() {
        tracker = StageHandoffTracker.getInstance();
        tracker.clear();
        registry = new SimpleMeterRegistry();
        tracker.bindRegistry(registry);
    }

    @Test
    void testCompleteLifecycleHandoffMetrology() {
        UUID cmdId = UUID.randomUUID();
        UUID potId = UUID.randomUUID();
        UUID potEventId = UUID.randomUUID();
        UUID completionEventId = UUID.randomUUID();

        // T0: Admission
        tracker.onCommandAdmitted(cmdId);

        // T1: Command worker starts attempt
        long t1 = System.nanoTime();
        tracker.onAttemptStarting(COMMAND_ID, cmdId, t1);

        // Link events during WRITE transaction
        tracker.linkCommandEvents(cmdId, potId, 1L, potEventId, completionEventId);

        // T2: Command write transaction commits
        long t2 = t1 + 5_000_000L; // +5ms
        tracker.onAttemptFinished(COMMAND_ID, cmdId, ConsumptionEngine.Status.COMMITTED, t2);

        // T3 & T4: PotVersion projection
        long t3Pv = t2 + 3_000_000L; // +3ms wait
        tracker.onAttemptStarting(POT_VERSION_ID, potEventId, t3Pv);
        long t4Pv = t3Pv + 4_000_000L; // +4ms processing
        tracker.onAttemptFinished(POT_VERSION_ID, potEventId, ConsumptionEngine.Status.COMMITTED, t4Pv);

        // T3 & T4: PotBalance projection
        long t3Pb = t2 + 4_000_000L; // +4ms wait
        tracker.onAttemptStarting(POT_BALANCE_ID, potEventId, t3Pb);
        long t4Pb = t3Pb + 3_500_000L; // +3.5ms processing
        tracker.onAttemptFinished(POT_BALANCE_ID, potEventId, ConsumptionEngine.Status.COMMITTED, t4Pb);

        // T3 & T4: CommandResult projection
        long t3Cr = t2 + 2_000_000L; // +2ms wait
        tracker.onAttemptStarting(COMMAND_RESULT_ID, completionEventId, t3Cr);
        long t4Cr = t3Cr + 2_500_000L; // +2.5ms processing
        tracker.onAttemptFinished(COMMAND_RESULT_ID, completionEventId, ConsumptionEngine.Status.COMMITTED, t4Cr);

        // Verify snapshot
        var snapshot = tracker.snapshot(10, null);
        assertEquals(1, snapshot.get("totalTracked"));
        var records = (java.util.List<?>) snapshot.get("records");
        assertEquals(1, records.size());

        @SuppressWarnings("unchecked")
        var rec = (java.util.Map<String, Object>) records.getFirst();
        assertEquals(cmdId.toString(), rec.get("commandId"));
        assertEquals(potId.toString(), rec.get("potId"));
        assertEquals(1L, rec.get("resultingVersion"));
        assertNotNull(rec.get("commandWaitMs"));
        assertNotNull(rec.get("commandProcessingMs"));
        assertEquals(5.0, (Double) rec.get("commandProcessingMs"), 0.1);

        @SuppressWarnings("unchecked")
        var projs = (java.util.Map<String, Object>) rec.get("projections");
        assertTrue(projs.containsKey("PotVersion"));
        assertTrue(projs.containsKey("PotBalance"));
        assertTrue(projs.containsKey("CommandResult"));

        @SuppressWarnings("unchecked")
        var pvMap = (java.util.Map<String, Object>) projs.get("PotVersion");
        assertEquals(3.0, (Double) pvMap.get("readWaitMs"), 0.1);
        assertEquals(4.0, (Double) pvMap.get("readProcessingMs"), 0.1);

        @SuppressWarnings("unchecked")
        var pbMap = (java.util.Map<String, Object>) projs.get("PotBalance");
        assertEquals(4.0, (Double) pbMap.get("readWaitMs"), 0.1);
        assertEquals(3.5, (Double) pbMap.get("readProcessingMs"), 0.1);

        // Verify C-03 readiness: max of pv and pb t4
        assertNotNull(rec.get("c03ReadyEpochMs"));

        // Verify Micrometer meters recorded
        var waitTimer = registry.find("pocoma.v3.handoff.wait").tag("consumption", "Command").timer();
        assertNotNull(waitTimer);
        assertEquals(1, waitTimer.count());

        var procTimer = registry.find("pocoma.v3.handoff.processing").tag("consumption", "Command").timer();
        assertNotNull(procTimer);
        assertEquals(1, procTimer.count());

        var pvWaitTimer = registry.find("pocoma.v3.handoff.wait").tag("consumption", "PotVersion").timer();
        assertNotNull(pvWaitTimer);
        assertEquals(1, pvWaitTimer.count());

        var pbProcTimer = registry.find("pocoma.v3.handoff.processing").tag("consumption", "PotBalance").timer();
        assertNotNull(pbProcTimer);
        assertEquals(1, pbProcTimer.count());
    }

    @Test
    void testRolledBackAttemptDoesNotRecordCommittedMetrics() {
        UUID cmdId = UUID.randomUUID();
        tracker.onCommandAdmitted(cmdId);

        long t1 = System.nanoTime();
        tracker.onAttemptStarting(COMMAND_ID, cmdId, t1);

        // Rolled back attempt
        long t2 = t1 + 3_000_000L;
        tracker.onAttemptFinished(COMMAND_ID, cmdId, ConsumptionEngine.Status.ROLLED_BACK, t2);

        var snapshot = tracker.snapshot(10, null);
        @SuppressWarnings("unchecked")
        var rec = (java.util.Map<String, Object>) ((java.util.List<?>) snapshot.get("records")).getFirst();
        assertNull(rec.get("commandProcessingMs"));
        assertNull(rec.get("t2EpochMs"));

        var procTimer = registry.find("pocoma.v3.handoff.processing").tag("consumption", "Command").timer();
        assertNull(procTimer);
    }

    @Test
    void testRobustAgainstNullsAndMissingEntries() {
        assertDoesNotThrow(() -> tracker.onCommandAdmitted(null));
        assertDoesNotThrow(() -> tracker.onAttemptStarting(null, null, 0));
        assertDoesNotThrow(() -> tracker.onAttemptFinished(null, null, null, 0));
        assertDoesNotThrow(() -> tracker.linkCommandEvents(null, null, null, null, null));
    }

    @Test
    void testUnauthenticatedSnapshotSanitizesBusinessIdentifiers() {
        UUID cmdId = UUID.randomUUID();
        UUID potId = UUID.randomUUID();
        UUID potEventId = UUID.randomUUID();
        UUID completionEventId = UUID.randomUUID();

        tracker.onCommandAdmitted(cmdId);
        long t1 = System.nanoTime();
        tracker.onAttemptStarting(COMMAND_ID, cmdId, t1);
        tracker.linkCommandEvents(cmdId, potId, 1L, potEventId, completionEventId);
        long t2 = t1 + 5_000_000L;
        tracker.onAttemptFinished(COMMAND_ID, cmdId, ConsumptionEngine.Status.COMMITTED, t2);

        // Authenticated snapshot contains business IDs
        var authSnapshot = tracker.snapshot(10, null, true);
        @SuppressWarnings("unchecked")
        var authRec = (java.util.Map<String, Object>) ((java.util.List<?>) authSnapshot.get("records")).getFirst();
        assertEquals(cmdId.toString(), authRec.get("commandId"));
        assertEquals(potId.toString(), authRec.get("potId"));
        assertEquals(1L, authRec.get("resultingVersion"));
        assertEquals(potEventId.toString(), authRec.get("potEventId"));

        // Unauthenticated snapshot omits all business IDs
        var unauthSnapshot = tracker.snapshot(10, null, false);
        @SuppressWarnings("unchecked")
        var unauthRec = (java.util.Map<String, Object>) ((java.util.List<?>) unauthSnapshot.get("records")).getFirst();
        assertEquals(cmdId.toString(), unauthRec.get("commandId"));
        assertNull(unauthRec.get("potId"));
        assertNull(unauthRec.get("resultingVersion"));
        assertNull(unauthRec.get("potEventId"));
        assertNull(unauthRec.get("completionEventId"));
        // Technical timing metrics must remain available
        assertNotNull(unauthRec.get("commandProcessingMs"));
    }
}
