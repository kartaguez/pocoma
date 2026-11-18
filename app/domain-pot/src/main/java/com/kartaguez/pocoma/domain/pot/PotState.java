package com.kartaguez.pocoma.domain.pot;


import com.kartaguez.pocoma.domain.pot.revision.InconsistentPotFactsException;
import com.kartaguez.pocoma.domain.pot.revision.Versioned;
import java.util.Objects;

public record PotState(Versioned<PotHeader> header, Versioned<PotDetails> details) {
    public PotState {
        Objects.requireNonNull(header);
        Objects.requireNonNull(details);
        if (!header.value().potId().equals(details.value().potId()) || !header.value().potId().equals(details.value().potId())) throw new InconsistentPotFactsException("Mixed composition");
    }
}
