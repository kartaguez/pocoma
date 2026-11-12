package com.kartaguez.pocoma.infra.observability.micrometer.v3;
import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionCatalog;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId;

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
        tracker = new StageHandoffTracker();
        tracker.clear();
        registry = new SimpleMeterRegistry();
        tracker.bindRegistry(registry);
    }

    @Test
    void independentlyComposedScopesCannotWakeOrClearEachOther() {
        var first=new StageHandoffTracker();var second=new StageHandoffTracker();
        var a=new java.util.concurrent.atomic.AtomicInteger();var b=new java.util.concurrent.atomic.AtomicInteger();
        first.registerWakeSignal(a::incrementAndGet);second.registerWakeSignal(b::incrementAndGet);
        first.onCommandAdmitted(new UUID(0,1));second.onCommandAdmitted(new UUID(0,2));
        first.clear();second.onCommandAdmitted(new UUID(0,3));
        assertEquals(1,a.get());assertEquals(2,b.get());
        assertEquals(0,((java.util.List<?>)first.snapshot(10,null).get("records")).size());
        assertEquals(2,((java.util.List<?>)second.snapshot(10,null).get("records")).size());
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
        tracker.onAttemptFinished(COMMAND_ID, cmdId, ConsumptionProtocol.Status.COMMITTED, t2);

        // T3 & T4: PotVersion projection
        long t3Pv = t2 + 3_000_000L; // +3ms wait
        tracker.onAttemptStarting(POT_VERSION_ID, potEventId, t3Pv);
        long t4Pv = t3Pv + 4_000_000L; // +4ms processing
        tracker.onAttemptFinished(POT_VERSION_ID, potEventId, ConsumptionProtocol.Status.COMMITTED, t4Pv);

        // T3 & T4: PotBalance projection
        long t3Pb = t2 + 4_000_000L; // +4ms wait
        tracker.onAttemptStarting(POT_BALANCE_ID, potEventId, t3Pb);
        long t4Pb = t3Pb + 3_500_000L; // +3.5ms processing
        tracker.onAttemptFinished(POT_BALANCE_ID, potEventId, ConsumptionProtocol.Status.COMMITTED, t4Pb);

        // T3 & T4: CommandResult projection
        long t3Cr = t2 + 2_000_000L; // +2ms wait
        tracker.onAttemptStarting(COMMAND_RESULT_ID, completionEventId, t3Cr);
        long t4Cr = t3Cr + 2_500_000L; // +2.5ms processing
        tracker.onAttemptFinished(COMMAND_RESULT_ID, completionEventId, ConsumptionProtocol.Status.COMMITTED, t4Cr);

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
        tracker.onAttemptFinished(COMMAND_ID, cmdId, ConsumptionProtocol.Status.ROLLED_BACK, t2);

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
        tracker.onAttemptFinished(COMMAND_ID, cmdId, ConsumptionProtocol.Status.COMMITTED, t2);

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
        assertNull(unauthRec.get("commandId"));
        assertEquals(java.util.Map.of(), unauthSnapshot.get("eventProjections"));
        assertNull(unauthRec.get("potId"));
        assertNull(unauthRec.get("resultingVersion"));
        assertNull(unauthRec.get("potEventId"));
        assertNull(unauthRec.get("completionEventId"));
        // Technical timing metrics must remain available
        assertNotNull(unauthRec.get("commandProcessingMs"));
    }

    @Test
    void testDetailedMilestoneDecompositionA1ToA4() {
        UUID cmdId = UUID.randomUUID();
        var def = com.kartaguez.pocoma.domain.consumption.v3.ConsumptionCatalog.definitions().getFirst(); // Command

        // T0: Admission
        tracker.onCommandAdmitted(cmdId);
        var recBefore = tracker.snapshot(1, null);
        @SuppressWarnings("unchecked")
        var rec0 = (java.util.Map<String, Object>) ((java.util.List<?>) recBefore.get("records")).getFirst();
        assertNotNull(rec0.get("t0EpochMs"));
        assertEquals("MISSING_T0A", rec0.get("missingDataReason"));

        // T0a: Discovery candidate
        long t0a = System.nanoTime();
        tracker.onCandidateDiscovered(def, 2, cmdId, t0a);

        // T0b: Slot persisted
        long t0b = t0a + 1_500_000L; // +1.5ms
        tracker.onSlotAdmitted(def, 2, cmdId, t0b);

        // T0c: Slot acquired
        long t0c = t0b + 8_000_000L; // +8.0ms
        tracker.onSlotAcquired(COMMAND_ID, cmdId, 2, t0c);

        // T1: Attempt start
        long t1 = t0c + 2_500_000L; // +2.5ms
        tracker.onAttemptStarting(COMMAND_ID, cmdId, t1);

        // T2: Attempt finished
        long t2 = t1 + 4_000_000L; // +4.0ms
        tracker.onAttemptFinished(COMMAND_ID, cmdId, ConsumptionProtocol.Status.COMMITTED, t2);

        var snapshot = tracker.snapshot(10, null);
        @SuppressWarnings("unchecked")
        var rec = (java.util.Map<String, Object>) ((java.util.List<?>) snapshot.get("records")).getFirst();

        assertEquals(2, rec.get("partition"));
        assertEquals(1, rec.get("attempts"));
        assertEquals("COMMITTED", rec.get("status"));
        assertNull(rec.get("missingDataReason"));

        Double a1 = (Double) rec.get("waitBeforeDiscoveryMs");
        Double a2 = (Double) rec.get("discoveryToSlotMs");
        Double a3 = (Double) rec.get("slotAcquisitionWaitMs");
        Double a4 = (Double) rec.get("acquisitionToAttemptMs");
        Double totalWait = (Double) rec.get("commandWaitMs");
        Double b = (Double) rec.get("commandProcessingMs");

        assertNotNull(a1);
        assertNotNull(a2);
        assertNotNull(a3);
        assertNotNull(a4);
        assertNotNull(totalWait);
        assertNotNull(b);

        assertEquals(1.5, a2, 0.05);
        assertEquals(8.0, a3, 0.05);
        assertEquals(2.5, a4, 0.05);
        assertEquals(4.0, b, 0.05);

        // Check identity: A1 + A2 + A3 + A4 == totalWait (within float rounding)
        assertEquals(totalWait, a1 + a2 + a3 + a4, 0.05);

        // Check partition stats
        @SuppressWarnings("unchecked")
        var partitionStats = (java.util.Map<String, Object>) snapshot.get("partitionStats");
        assertNotNull(partitionStats);
        @SuppressWarnings("unchecked")
        var p2Map = (java.util.Map<String, Object>) partitionStats.get("partition_2");
        assertEquals(1, p2Map.get("totalTracked"));
        assertEquals(1, p2Map.get("discovered"));
        assertEquals(1, p2Map.get("slotPersisted"));
        assertEquals(1, p2Map.get("slotAcquired"));
        assertEquals(1, p2Map.get("attemptStarted"));
        assertEquals(1, p2Map.get("attemptFinished"));
    }
}
