package com.kartaguez.pocoma.engine.admit.command.port.out;
import com.kartaguez.pocoma.contracts.command.RecordedCommand;
/** Insert-only durable intake boundary. */
public interface RecordedCommandInsertionPort { void insert(RecordedCommand command); }
