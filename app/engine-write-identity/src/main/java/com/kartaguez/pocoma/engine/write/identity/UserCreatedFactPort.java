package com.kartaguez.pocoma.engine.write.identity;

import java.util.UUID;

import com.kartaguez.pocoma.domain.identity.PocomaUserId;

public interface UserCreatedFactPort {
    com.kartaguez.pocoma.port.transaction.EventRef append(UUID requestId, PocomaUserId userId);
}
