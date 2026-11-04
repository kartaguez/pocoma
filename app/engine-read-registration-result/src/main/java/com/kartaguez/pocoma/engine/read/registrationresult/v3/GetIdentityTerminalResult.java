package com.kartaguez.pocoma.engine.read.registrationresult.v3;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import java.util.*;
public final class GetIdentityTerminalResult {
    /** Empty means absent or foreign; technical unavailability is an exception. */
    @FunctionalInterface
    public interface Query { Optional<IdentityTerminalResult> get(UUID request, ExternalIdentity eui); }
    private final IdentityTerminalResultStore store;
    public GetIdentityTerminalResult(IdentityTerminalResultStore store) { this.store=Objects.requireNonNull(store); }
    public Optional<IdentityTerminalResult> get(UUID request,ExternalIdentity authenticatedEui) {
        Objects.requireNonNull(authenticatedEui); return store.find(request).filter(v -> v.requesterEui().equals(authenticatedEui));
    }
}
