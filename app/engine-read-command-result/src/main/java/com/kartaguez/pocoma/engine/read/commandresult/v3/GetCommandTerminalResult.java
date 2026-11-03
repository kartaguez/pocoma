package com.kartaguez.pocoma.engine.read.commandresult.v3;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import java.util.*;
public final class GetCommandTerminalResult {
    private final CommandTerminalResultStore store;
    public GetCommandTerminalResult(CommandTerminalResultStore store) { this.store=Objects.requireNonNull(store); }
    public Optional<CommandTerminalResult> get(UUID request,ExternalIdentity authenticatedEui) {
        Objects.requireNonNull(authenticatedEui); return store.find(request).filter(v -> v.requesterEui().equals(authenticatedEui));
    }
}
