package com.kartaguez.pocoma.domain.pot.v3;

import java.util.*;
import java.time.LocalDate;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;

public record PotDetails(PotId potId, Label label, UserId creatorId) {
    public PotDetails {
        Objects.requireNonNull(potId);
        Objects.requireNonNull(label);
        Objects.requireNonNull(creatorId);

    }
}
