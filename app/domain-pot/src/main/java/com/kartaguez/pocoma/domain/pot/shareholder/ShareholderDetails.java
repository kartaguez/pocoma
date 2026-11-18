package com.kartaguez.pocoma.domain.pot.shareholder;

import java.util.Objects;
import java.util.Optional;

import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

public record ShareholderDetails(PotId potId, ShareholderId shareholderId, Name name, Optional<UserId> userId) {
    public ShareholderDetails {
        Objects.requireNonNull(potId);
        Objects.requireNonNull(shareholderId);
        Objects.requireNonNull(name);
        Objects.requireNonNull(userId);

    }
}
