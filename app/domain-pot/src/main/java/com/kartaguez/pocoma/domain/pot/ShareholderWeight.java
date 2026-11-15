package com.kartaguez.pocoma.domain.pot;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

public record ShareholderWeight(PotId potId, ShareholderId shareholderId, Weight defaultWeight) {
    public ShareholderWeight {
        Objects.requireNonNull(potId);
        Objects.requireNonNull(shareholderId);
        Objects.requireNonNull(defaultWeight);

    }
}
