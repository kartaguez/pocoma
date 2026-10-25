package com.kartaguez.pocoma.engine.write.pot.v3;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.v3.PotHistoryPort;

import java.util.*;

/** Internal historical calculation. The caller supplies a coherent WRITE snapshot transaction. */
public final class CalculatePotBalances {
    private final PotHistoryPort history;
    private final PotBalanceCalculator calculator;

    public CalculatePotBalances(PotHistoryPort history, PotBalanceCalculator calculator) {
        this.history = Objects.requireNonNull(history);
        this.calculator = Objects.requireNonNull(calculator);
    }

    public Optional<PotBalanceCalculator.Balances> atVersion(PotId potId, long version) {
        var state =
                history.financialAtVersion(potId, version)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown Pot"));
        if (!state.potId().equals(potId) || state.version() != version)
            throw new InconsistentPotFactsException(
                    "Financial source differs from requested Pot/version");
        return calculator.calculate(state);
    }
}
