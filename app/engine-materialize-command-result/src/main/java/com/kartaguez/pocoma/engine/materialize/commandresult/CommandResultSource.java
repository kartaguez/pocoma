package com.kartaguez.pocoma.engine.materialize.commandresult;

import java.util.UUID;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.consume.command.model.CommandOutcome;

/** Values independently reloaded from the terminal Event, outcome and recorded Command. */
public record CommandResultSource(UUID terminalCommandId, String terminalEventType,
		CommandOutcome outcome, UUID recordedCommandId, ExternalIdentity requester) {}
