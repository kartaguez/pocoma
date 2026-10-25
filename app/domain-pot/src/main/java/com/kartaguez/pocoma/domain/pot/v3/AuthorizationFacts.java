package com.kartaguez.pocoma.domain.pot.v3;

import java.util.*;
import java.time.LocalDate;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;

/** WRITE-derived facts. The actor is resolved by the application authority, never by a client flag. */
public record AuthorizationFacts(PotId potId, long workingVersion, UserId creatorId, UserId actorUserId, boolean activelyAttached) {
    public AuthorizationFacts {
        Objects.requireNonNull(potId); Objects.requireNonNull(creatorId); Objects.requireNonNull(actorUserId);
        if (workingVersion < 1) throw new InconsistentPotFactsException("Invalid fact version");
    }
    public boolean creator() { return creatorId.equals(actorUserId); }
    public boolean canMutate() { return creator() || activelyAttached; }
}
