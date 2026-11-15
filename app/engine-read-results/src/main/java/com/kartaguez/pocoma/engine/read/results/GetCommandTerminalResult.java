package com.kartaguez.pocoma.engine.read.results;
import com.kartaguez.pocoma.domain.projection.CommandTerminalResult;
import com.kartaguez.pocoma.port.projection.CommandTerminalResultStore;
import com.kartaguez.pocoma.contract.readquery.CommandResultRead;
import com.kartaguez.pocoma.contract.readquery.CommandResultRead.*;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import java.util.*;
public final class GetCommandTerminalResult implements CommandResultRead {
    /** Empty means absent or foreign; technical unavailability is an exception. */
    private final CommandTerminalResultStore store;
    public GetCommandTerminalResult(CommandTerminalResultStore store) { this.store=Objects.requireNonNull(store); }
    public Optional<CommandTerminalResult> get(UUID request,ExternalIdentity authenticatedEui) {
        Objects.requireNonNull(authenticatedEui); return store.find(request).filter(v -> v.requesterEui().equals(authenticatedEui));
    }
}
