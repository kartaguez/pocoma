package com.kartaguez.pocoma.domain.pot.shareholder;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

public record ShareholderHeader(PotId potId, ShareholderId shareholderId, boolean deleted) {
    public ShareholderHeader {
        Objects.requireNonNull(potId);
        Objects.requireNonNull(shareholderId);

    }
}
