package com.kartaguez.pocoma.engine.write.pot.port.execution;

import com.kartaguez.pocoma.contract.consumption.spi.RecoveryObservation;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

import java.util.UUID;

/** Business evidence only, in the Engine's existing read snapshot. */
public interface PotCommandObservation {
    RecoveryObservation observe(UUID requestId, RecoveryReadContext context);
}
