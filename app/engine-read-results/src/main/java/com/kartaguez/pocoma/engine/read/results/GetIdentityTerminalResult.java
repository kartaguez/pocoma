package com.kartaguez.pocoma.engine.read.results;
import com.kartaguez.pocoma.domain.projection.IdentityTerminalResult;
import com.kartaguez.pocoma.port.projection.IdentityTerminalResultStore;
import com.kartaguez.pocoma.contract.readquery.IdentityResultRead;
import com.kartaguez.pocoma.contract.readquery.IdentityResultRead.*;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import java.util.*;
public final class GetIdentityTerminalResult implements IdentityResultRead {
    /** Empty means absent or foreign; technical unavailability is an exception. */
    private final IdentityTerminalResultStore store;
    public GetIdentityTerminalResult(IdentityTerminalResultStore store) { this.store=Objects.requireNonNull(store); }
    public Optional<IdentityTerminalResult> get(UUID request,ExternalIdentity authenticatedEui) {
        Objects.requireNonNull(authenticatedEui); return store.find(request).filter(v -> v.requesterEui().equals(authenticatedEui));
    }
}
