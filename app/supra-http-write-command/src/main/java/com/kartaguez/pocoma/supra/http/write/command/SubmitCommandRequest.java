package com.kartaguez.pocoma.supra.http.write.command;

import java.util.UUID;

public record SubmitCommandRequest(String commandType, UUID bindingId, Object payload) {}
