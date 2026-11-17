package com.kartaguez.pocoma.engine.materialize.results;
import com.kartaguez.pocoma.domain.projection.CommandTerminalResult;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.util.UUID;
public interface CommandTerminalSource { CommandTerminalResult load(UUID event,RecoveryReadContext context); }
