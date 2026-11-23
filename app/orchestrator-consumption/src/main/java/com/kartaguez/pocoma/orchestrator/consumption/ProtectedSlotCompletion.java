package com.kartaguez.pocoma.orchestrator.consumption;

import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;
import com.kartaguez.pocoma.contract.consumption.spi.ClaimedSlot;
import com.kartaguez.pocoma.contract.consumption.spi.ConsumptionSlotStore;
import com.kartaguez.pocoma.port.transaction.AttemptTransactions;

/** Single-use finalization completion handle that enforces protected slot deletion. */
final class ProtectedSlotCompletion implements SlotCompletion {
    private final ClaimedSlot claim;
    private final ConsumptionParticipant.Prepared prepared;
    private final ConsumptionSlotStore slots;
    private final AttemptTransactions transactions;
    private final Thread thread = Thread.currentThread();
    private volatile boolean active = true;
    private boolean called;
    private volatile Throwable failure;

    ProtectedSlotCompletion(
            ClaimedSlot claim,
            ConsumptionParticipant.Prepared prepared,
            ConsumptionSlotStore slots,
            AttemptTransactions transactions) {
        this.claim = claim;
        this.prepared = prepared;
        this.slots = slots;
        this.transactions = transactions;
    }

    @Override
    public synchronized void complete() {
        try {
            if (!active || called || Thread.currentThread() != thread) {
                throw new IllegalStateException("Completion is single-use in its final callback");
            }
            called = true;
            prepared.requireCompletionReady();
            slots.deleteProtected(claim);
        } catch (RuntimeException | Error fault) {
            failure = fault;
            transactions.poison(fault);
            throw fault;
        }
    }

    void deactivate() {
        active = false;
    }

    synchronized void requireCompleted() {
        if (!called || failure != null) {
            var fault = new IllegalStateException("Missing or failed completion", failure);
            transactions.poison(fault);
            throw fault;
        }
    }
}
