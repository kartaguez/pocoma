package com.kartaguez.pocoma.domain.pot.v3;

import java.util.*;
import java.time.LocalDate;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;

public final class PotTransitions {
    private PotTransitions() {}
    public static TransitionResult<PotState> create(PotId id, Label label, UserId creator, boolean alreadyExists) {
        var state = new PotState(new Versioned<>(1, 1, new PotHeader(id, false)), new Versioned<>(1, 1, new PotDetails(id, label, creator)));
        return alreadyExists ? TransitionChecks.reject(TransitionResult.Rejection.ALREADY_EXISTS) : TransitionResult.Changed.propose(0, state);
    }
    public static TransitionResult<Versioned<PotDetails>> updateDetails(PotFacts pot, long observed, Versioned<PotDetails> current, Label label) {
        Objects.requireNonNull(label);
        TransitionChecks.revision(pot, current.value().potId(), current);
        if (!current.value().creatorId().equals(pot.authorization().creatorId())) throw new InconsistentPotFactsException("Mixed creator facts");
        var rejection = TransitionChecks.permission(pot, observed, false);
        if (rejection != null) return TransitionChecks.reject(rejection);
        if (current.activeFrom() > observed) return TransitionChecks.reject(TransitionResult.Rejection.OBSOLETE);
        if (current.value().label().equals(label)) return new TransitionResult.Unchanged<>(pot.workingVersion());
        var next = new PotDetails(pot.potId(), label, current.value().creatorId());
        return TransitionResult.Changed.propose(pot.workingVersion(), TransitionChecks.next(current, next, TransitionChecks.candidate(pot)));
    }
    public static TransitionResult<Versioned<PotHeader>> delete(PotFacts pot, long observed) {
        var rejection = TransitionChecks.permission(pot, observed, true);
        if (rejection != null) return TransitionChecks.reject(rejection);
        if (observed != pot.workingVersion()) return TransitionChecks.reject(TransitionResult.Rejection.OBSOLETE);
        return TransitionResult.Changed.propose(pot.workingVersion(), TransitionChecks.next(pot.header(), new PotHeader(pot.potId(), true), TransitionChecks.candidate(pot)));
    }
}
