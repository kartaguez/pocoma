package com.kartaguez.pocoma.engine.write.pot.port.persistence.v3;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;

import java.time.Instant;
import java.util.*;

/**
 * Low-level revisions only, under the caller's transaction on the same WRITE data source. The
 * caller rolls back on every technical failure, even one it catches. No method commits, retries,
 * authorizes a request, advances a CAS, or publishes an event. After C's version protection,
 * recordVersion and all changed revisions share that transaction. Creation methods insert all
 * components; deletion revises only a Header.
 */
public interface PotRevisionPort {
    void createPot(TransitionResult.Changed<PotState> proposal, Instant recordedAt);

    void recordVersion(
            PotId potId, long expectedVersion, long candidateVersion, Instant recordedAt);

    void createShareholders(PotId potId, TransitionResult.Changed<List<ShareholderState>> proposal);

    void createExpense(PotId potId, TransitionResult.Changed<ExpenseState> proposal);

    void revisePotDetails(PotId potId, TransitionResult.Changed<Versioned<PotDetails>> proposal);

    void revisePotHeader(PotId potId, TransitionResult.Changed<Versioned<PotHeader>> proposal);

    void reviseShareholderDetails(
            PotId potId,
            TransitionResult.Changed<Map<ShareholderId, Versioned<ShareholderDetails>>> proposal);

    void reviseShareholderWeights(
            PotId potId,
            TransitionResult.Changed<Map<ShareholderId, Versioned<ShareholderWeight>>> proposal);

    void reviseShareholderHeader(
            PotId potId, TransitionResult.Changed<Versioned<ShareholderHeader>> proposal);

    void reviseExpenseDetails(
            PotId potId, TransitionResult.Changed<Versioned<ExpenseDetails>> proposal);

    void reviseExpenseFinancials(
            PotId potId, TransitionResult.Changed<Versioned<ExpenseFinancials>> proposal);

    void reviseExpenseHeader(
            PotId potId, TransitionResult.Changed<Versioned<ExpenseHeader>> proposal);
}
