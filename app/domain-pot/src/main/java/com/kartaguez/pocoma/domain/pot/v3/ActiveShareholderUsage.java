package com.kartaguez.pocoma.domain.pot.v3;

import java.util.*;
import java.time.LocalDate;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;

/** Exhaustive over payer OR participant in every active Expense at this Pot version. */
public record ActiveShareholderUsage(PotId potId, long workingVersion, ShareholderId shareholderId,
                                     boolean referenced, boolean exhaustive) {
    public ActiveShareholderUsage {
        Objects.requireNonNull(potId); Objects.requireNonNull(shareholderId);
        if (workingVersion < 1 || !exhaustive) throw new InconsistentPotFactsException("Incomplete usage evidence");
    }
}
