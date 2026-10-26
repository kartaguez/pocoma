package com.kartaguez.pocoma.engine.write.pot.port.execution.v3;

import com.kartaguez.pocoma.engine.consumption.v3.RecoveryObservation;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import java.util.UUID;

/** Business evidence only, in the Engine's existing read snapshot. */
public interface PotCommandObservation {
    RecoveryObservation observe(UUID requestId, RecoveryReadContext context);
}
