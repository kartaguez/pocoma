package com.kartaguez.pocoma.supra.web.write;

import java.util.UUID;

public record SubmitCommandRequest(String commandType, UUID bindingId, Object payload) {}
