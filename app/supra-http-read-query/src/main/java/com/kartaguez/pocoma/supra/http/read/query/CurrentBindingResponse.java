package com.kartaguez.pocoma.supra.http.read.query;

import java.util.UUID;

public record CurrentBindingResponse(UUID userId, UUID bindingId, long bindingRevision, String status) {}
