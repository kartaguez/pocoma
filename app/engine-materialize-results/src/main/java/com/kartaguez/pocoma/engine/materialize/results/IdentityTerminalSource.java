package com.kartaguez.pocoma.engine.materialize.results;
import com.kartaguez.pocoma.domain.projection.IdentityTerminalResult;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.util.UUID;
public interface IdentityTerminalSource { IdentityTerminalResult load(UUID event,RecoveryReadContext context); }
