package com.kartaguez.pocoma.runtime.command.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId;
import com.kartaguez.pocoma.engine.consumption.v3.PartitionLease;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

class I5PhysicalWindowTest {
    @Test
    void openingClosureAndIncarnationAreIndependentOfSuccess() {
        var lease =
                new PartitionLease(
                        new ConsumptionId(UUID.randomUUID()),
                        0,
                        UUID.randomUUID(),
                        Instant.now().plusSeconds(90));
        var w = new I5PhysicalWindow.Window(lease, 100);
        assertFalse(w.covers(lease, 99, 101));
        assertTrue(w.covers(lease, 100, 110));
        assertFalse(
                w.covers(
                        new PartitionLease(
                                lease.consumptionId(), 0, UUID.randomUUID(), lease.ownedUntil()),
                        100,
                        110));
        w.end = 120;
        assertTrue(w.covers(lease, 110, 120));
        assertFalse(w.covers(lease, 110, 121));
        assertFalse(w.covers(lease, 110, 109));
    }

    @Test
    void missingCoverageOrConflictingTransactionCannotPass() {
        assertTrue(I5PhysicalWindow.witnessed(true, true, false));
        assertFalse(I5PhysicalWindow.witnessed(false, true, false));
        assertFalse(I5PhysicalWindow.witnessed(true, true, true));
        assertThrows(AssertionError.class, () -> I5PhysicalWindow.witnessed(true, false, false));
    }
}
