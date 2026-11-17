package com.kartaguez.pocoma.engine.read.results;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.contract.readquery.IdentityResultRead;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.projection.IdentityTerminalResult;
import com.kartaguez.pocoma.port.projection.IdentityTerminalResultStore;
public final class GetIdentityTerminalResult implements IdentityResultRead {
    /** Empty means absent or foreign; technical unavailability is an exception. */
    private final IdentityTerminalResultStore store;
    public GetIdentityTerminalResult(IdentityTerminalResultStore store) { this.store=Objects.requireNonNull(store); }
    public Optional<IdentityTerminalResult> get(UUID request,ExternalIdentity authenticatedEui) {
        Objects.requireNonNull(authenticatedEui); return store.find(request).filter(v -> v.requesterEui().equals(authenticatedEui));
    }
}
