package com.kartaguez.pocoma.domain.pot.v3;

import java.util.*;
import java.time.LocalDate;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;

public record PotState(Versioned<PotHeader> header, Versioned<PotDetails> details) {
    public PotState {
        Objects.requireNonNull(header);
        Objects.requireNonNull(details);
        if (!header.value().potId().equals(details.value().potId()) || !header.value().potId().equals(details.value().potId())) throw new InconsistentPotFactsException("Mixed composition");
    }
}
