package com.kartaguez.pocoma.domain.pot.v3;

import java.util.*;
import java.time.LocalDate;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;

public record ShareholderState(Versioned<ShareholderHeader> header, Versioned<ShareholderDetails> details, Versioned<ShareholderWeight> weight) {
    public ShareholderState {
        Objects.requireNonNull(header);
        Objects.requireNonNull(details);
        Objects.requireNonNull(weight);
        if (!header.value().potId().equals(details.value().potId()) || !header.value().shareholderId().equals(details.value().shareholderId())) throw new InconsistentPotFactsException("Mixed composition");
        if (!header.value().potId().equals(weight.value().potId()) || !header.value().shareholderId().equals(weight.value().shareholderId())) throw new InconsistentPotFactsException("Mixed composition");
    }
}
