package com.kartaguez.pocoma.domain.pot.v3;

import java.util.*;
import java.time.LocalDate;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;

public record PotFacts(PotId potId, long workingVersion, Versioned<PotHeader> header, AuthorizationFacts authorization) {
    public PotFacts {
        Objects.requireNonNull(potId); Objects.requireNonNull(header); Objects.requireNonNull(authorization);
        if (workingVersion < 1 || !potId.equals(header.value().potId()) || !potId.equals(authorization.potId())
                || workingVersion != authorization.workingVersion() || header.activeFrom() > workingVersion)
            throw new InconsistentPotFactsException("Mixed Pot facts");
    }
}
