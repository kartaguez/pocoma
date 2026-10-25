package com.kartaguez.pocoma.domain.pot.v3;

import java.util.*;
import java.time.LocalDate;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;

public record PotHeader(PotId potId, boolean deleted) {
    public PotHeader {
        Objects.requireNonNull(potId);

    }
}
