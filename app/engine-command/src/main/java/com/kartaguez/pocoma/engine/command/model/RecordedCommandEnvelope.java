package com.kartaguez.pocoma.engine.command.model;

/** One explicitly versioned durable Command envelope. */
public sealed interface RecordedCommandEnvelope permits AuthorizationSnapshot, TargetCommandEnvelope {
	RecordedCommandEnvelopeVersion version();
}
