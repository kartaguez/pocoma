package com.kartaguez.pocoma.domain.pot;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

/** Exhaustive over payer OR participant in every active Expense at this Pot version. */
public record ActiveShareholderUsage(PotId potId, long workingVersion, ShareholderId shareholderId,
                                     boolean referenced, boolean exhaustive) {
    public ActiveShareholderUsage {
        Objects.requireNonNull(potId); Objects.requireNonNull(shareholderId);
        if (workingVersion < 1 || !exhaustive) throw new InconsistentPotFactsException("Incomplete usage evidence");
    }
}
