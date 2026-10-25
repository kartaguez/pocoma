package com.kartaguez.pocoma.domain.pot.v3;

import java.util.*;
import java.time.LocalDate;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;

public record ShareholderDetails(PotId potId, ShareholderId shareholderId, Name name, Optional<UserId> userId) {
    public ShareholderDetails {
        Objects.requireNonNull(potId);
        Objects.requireNonNull(shareholderId);
        Objects.requireNonNull(name);
        Objects.requireNonNull(userId);

    }
}
