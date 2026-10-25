package com.kartaguez.pocoma.engine.write.pot.port.execution.v3;

import com.kartaguez.pocoma.domain.pot.value.UserId;

import java.util.*;

/** Identity WRITE authority; implementations must protect both positive and negative facts. */
public interface PotActorAuthority {
    record Witness(long revision, Optional<UserId> actor) {
        public Witness {
            if (revision < 0) throw new IllegalArgumentException();
            Objects.requireNonNull(actor);
        }
    }

    Witness observe(PotCommand command);

    /** Lock the EUI occurrence, then compare the full witness, retaining its lock until commit. */
    boolean protect(PotCommand command, Witness observed);
}
