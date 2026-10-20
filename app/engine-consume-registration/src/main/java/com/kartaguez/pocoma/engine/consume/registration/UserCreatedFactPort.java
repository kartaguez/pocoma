package com.kartaguez.pocoma.engine.consume.registration;

import java.util.UUID;

import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;

public interface UserCreatedFactPort {
    com.kartaguez.pocoma.port.transaction.EventRef append(UUID requestId, PocomaUserId userId);
}
