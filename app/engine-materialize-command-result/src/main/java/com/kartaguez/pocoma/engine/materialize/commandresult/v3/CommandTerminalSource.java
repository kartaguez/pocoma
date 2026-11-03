package com.kartaguez.pocoma.engine.materialize.commandresult.v3;
import com.kartaguez.pocoma.engine.read.commandresult.v3.CommandTerminalResult;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import java.util.UUID;
public interface CommandTerminalSource { CommandTerminalResult load(UUID event,RecoveryReadContext context); }
