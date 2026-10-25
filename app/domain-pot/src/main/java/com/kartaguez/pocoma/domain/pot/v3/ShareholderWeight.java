package com.kartaguez.pocoma.domain.pot.v3;

import java.util.*;
import java.time.LocalDate;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;

public record ShareholderWeight(PotId potId, ShareholderId shareholderId, Weight defaultWeight) {
    public ShareholderWeight {
        Objects.requireNonNull(potId);
        Objects.requireNonNull(shareholderId);
        Objects.requireNonNull(defaultWeight);

    }
}
