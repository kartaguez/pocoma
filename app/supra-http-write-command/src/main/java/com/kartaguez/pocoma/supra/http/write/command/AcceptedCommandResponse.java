package com.kartaguez.pocoma.supra.http.write.command;

import java.util.UUID;

public record AcceptedCommandResponse(UUID commandId, String status) {
	public AcceptedCommandResponse(UUID commandId) { this(commandId, "ACCEPTED"); }
}
