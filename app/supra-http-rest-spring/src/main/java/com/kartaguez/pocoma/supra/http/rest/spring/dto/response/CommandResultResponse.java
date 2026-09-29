package com.kartaguez.pocoma.supra.http.rest.spring.dto.response;

import java.time.Instant;
import java.util.UUID;

public record CommandResultResponse(UUID commandId, String status, UUID potId, Long resultingVersion,
		String code, Instant resolvedAt) {
}
