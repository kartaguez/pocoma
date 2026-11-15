package com.kartaguez.pocoma.domain.pot;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public record PotHeader(PotId potId, boolean deleted) {
    public PotHeader {
        Objects.requireNonNull(potId);

    }
}
