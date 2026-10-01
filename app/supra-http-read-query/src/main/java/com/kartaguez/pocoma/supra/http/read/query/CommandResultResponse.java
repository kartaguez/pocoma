package com.kartaguez.pocoma.supra.http.read.query;

import java.time.Instant;
import java.util.UUID;

public record CommandResultResponse(UUID commandId, String status, UUID potId, Long resultingVersion,
		String code, Instant resolvedAt) {}
