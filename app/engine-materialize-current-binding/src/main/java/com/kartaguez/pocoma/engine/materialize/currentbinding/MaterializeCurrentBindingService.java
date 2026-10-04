package com.kartaguez.pocoma.engine.materialize.currentbinding;

import com.kartaguez.pocoma.domain.useridentity.currentbinding.CurrentBinding;
import com.kartaguez.pocoma.domain.useridentity.currentbinding.CurrentBindingStatus;
import com.kartaguez.pocoma.domain.useridentity.currentbinding.CurrentBindingApplyResult;
import com.kartaguez.pocoma.engine.materialize.currentbinding.port.CurrentBindingWritePort;

import static java.util.Objects.requireNonNull;

import java.time.Clock;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityAttached;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityBindingFact;

/** Applies an immutable fact directly to CURRENT_BINDING, without ProjectionTask. */
public final class MaterializeCurrentBindingService {
    private final CurrentBindingWritePort projection;
    private final Clock clock;

    public MaterializeCurrentBindingService(CurrentBindingWritePort projection, Clock clock) {
        this.projection = requireNonNull(projection);
        this.clock = requireNonNull(clock);
    }

    public CurrentBindingApplyResult apply(ExternalIdentityBindingFact fact) {
        requireNonNull(fact, "fact must not be null");
        var status = fact instanceof ExternalIdentityAttached
                ? CurrentBindingStatus.ATTACHED : CurrentBindingStatus.DETACHED;
        var userId = fact instanceof ExternalIdentityAttached attached ? attached.userId() : null;
        return projection.apply(new CurrentBinding(fact.externalIdentity(), fact.bindingRevision(), status, userId,
                status == CurrentBindingStatus.ATTACHED ? fact.bindingId() : null,
                fact.eventId(), clock.instant()));
    }
}
