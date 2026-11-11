package com.kartaguez.pocoma.engine.materialize.results.v3;
import com.kartaguez.pocoma.domain.projection.v3.IdentityTerminalResult;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import java.util.UUID;
public interface IdentityTerminalSource { IdentityTerminalResult load(UUID event,RecoveryReadContext context); }
