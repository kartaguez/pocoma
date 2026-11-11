package com.kartaguez.pocoma.supra.web.read;

import java.util.UUID;

public record CurrentBindingResponse(UUID userId, UUID bindingId, long bindingRevision, String status) {}
