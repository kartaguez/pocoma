package com.kartaguez.pocoma.engine.read.pot;
import com.kartaguez.pocoma.contract.readquery.ReadLimits;
import com.kartaguez.pocoma.contract.readquery.ReadPotResult;

import static com.kartaguez.pocoma.contract.readquery.ReadPotResult.*;

import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.port.projection.PotVisibilityStore;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

import java.util.*;

/** Per-page Binding resolution. Navigation grants no permission to open a Pot. */
public final class ReadNavigation {
    private final ReadPot pot;
    private final PotVisibilityStore visibility;
    private final ReadLimits limits;

    public ReadNavigation(ReadPot pot, PotVisibilityStore visibility, ReadLimits limits) {
        this.pot = Objects.requireNonNull(pot);
        this.visibility = Objects.requireNonNull(visibility);
        this.limits = Objects.requireNonNull(limits);
    }

    public ReadPotResult<List<UUID>> visible(
            ExternalIdentity eui, String token, UUID after, int limit, RecoveryReadContext c) {
        if (eui == null || token == null) return new InvalidInput<>();
        if (token.isBlank() || !limits.validPage(limit)) return new InvalidInput<>();
        var user = pot.user(eui, token, c);
        if (user.isEmpty()) return new NotFound<>();
        var values = visibility.visiblePots(user.get(), after, limit, c);
        return 40L * values.size() > limits.responseBudget()
                ? new Unavailable<>()
                : new Available<>(List.copyOf(values), 0);
    }
}
