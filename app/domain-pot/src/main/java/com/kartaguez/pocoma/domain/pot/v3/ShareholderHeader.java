package com.kartaguez.pocoma.domain.pot.v3;

import java.util.*;
import java.time.LocalDate;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;

public record ShareholderHeader(PotId potId, ShareholderId shareholderId, boolean deleted) {
    public ShareholderHeader {
        Objects.requireNonNull(potId);
        Objects.requireNonNull(shareholderId);

    }
}
