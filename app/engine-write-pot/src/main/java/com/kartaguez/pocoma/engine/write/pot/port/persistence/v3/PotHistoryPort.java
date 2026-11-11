package com.kartaguez.pocoma.engine.write.pot.port.persistence.v3;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.*;

import java.time.Instant;
import java.util.*;

/**
 * All reads use the caller's WRITE snapshot transaction (REPEATABLE_READ or SERIALIZABLE). Unknown
 * Pot is empty; missing/corrupt components of known objects are technical errors. No pagination may
 * be used to construct a negative reference-usage fact.
 */
public interface PotHistoryPort extends PotHistory {
    /** Complete grouped financial facts at V, including explicit zero-balance Shareholders.
     * No Details, current weights, previous balances or READ access. Caller owns the snapshot. */
    Optional<PotFinancialState> financialAtVersion(PotId potId, long version);

    Optional<Snapshot> atVersion(PotId potId, long version);

    Optional<SelectedPot> current(PotId potId, UserId actor, Selection selection);

    /** Historical grouped contents at the exact V, never authority for a current mutation. */
    Optional<SelectedPot> componentsAtVersion(
            PotId potId, long version, UserId actor, Selection selection);

    ActiveShareholderUsage activeUsage(PotId potId, long version, ShareholderId shareholderId);
}
