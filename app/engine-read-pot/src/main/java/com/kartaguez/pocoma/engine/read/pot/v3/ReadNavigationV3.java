package com.kartaguez.pocoma.engine.read.pot.v3;

import static com.kartaguez.pocoma.engine.read.pot.v3.ReadPotV3Result.*;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.port.projection.v3.PotVisibilityStore;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import java.util.*;

/** Per-page Binding resolution. Navigation grants no permission to open a Pot. */
public final class ReadNavigationV3 {
    private final ReadPotV3 pot;
    private final PotVisibilityStore visibility;
    private final ReadLimitsV3 limits;

    public ReadNavigationV3(ReadPotV3 pot, PotVisibilityStore visibility, ReadLimitsV3 limits) {
        this.pot = Objects.requireNonNull(pot);
        this.visibility = Objects.requireNonNull(visibility);
        this.limits = Objects.requireNonNull(limits);
    }

    public ReadPotV3Result<List<UUID>> visible(
            ExternalIdentity eui, String token, UUID after, int limit, RecoveryReadContext c) {
        if (eui == null
                || token == null
                || token.isBlank()
                || !limits.validPage(limit)) return new InvalidInput<>();
        var user = pot.user(eui, token, c);
        if (user.isEmpty()) return new NotFound<>();
        var values = visibility.visiblePots(user.get(), after, limit, c);
        return 40L * values.size() > limits.responseBudget()
                ? new Unavailable<>()
                : new Available<>(List.copyOf(values), 0);
    }
}
