package com.kartaguez.pocoma.supra.web.write;

import java.util.UUID;

public record AcceptedCommandResponse(UUID commandId, String status) {
	public AcceptedCommandResponse(UUID commandId) { this(commandId, "ACCEPTED"); }
}
