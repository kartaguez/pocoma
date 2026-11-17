package com.kartaguez.pocoma.domain.pot;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public record PotDetails(PotId potId, Label label, UserId creatorId) {
    public PotDetails {
        Objects.requireNonNull(potId);
        Objects.requireNonNull(label);
        Objects.requireNonNull(creatorId);

    }
}
