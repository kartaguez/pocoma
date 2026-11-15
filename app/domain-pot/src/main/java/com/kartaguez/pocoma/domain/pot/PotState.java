package com.kartaguez.pocoma.domain.pot;

import java.util.Objects;

public record PotState(Versioned<PotHeader> header, Versioned<PotDetails> details) {
    public PotState {
        Objects.requireNonNull(header);
        Objects.requireNonNull(details);
        if (!header.value().potId().equals(details.value().potId()) || !header.value().potId().equals(details.value().potId())) throw new InconsistentPotFactsException("Mixed composition");
    }
}
