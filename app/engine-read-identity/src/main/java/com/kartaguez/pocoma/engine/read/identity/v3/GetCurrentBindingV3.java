package com.kartaguez.pocoma.engine.read.identity.v3;
import com.kartaguez.pocoma.contract.readquery.BindingRead;
import com.kartaguez.pocoma.contract.readquery.BindingRead.*;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.identity.v3.ExternalUserIdentity;
import java.util.*;
import java.util.function.Function;
/** Inert internal own-EUI reader. Authentication is supplied by the future L5 boundary. */
public final class GetCurrentBindingV3 implements BindingRead {
    private final Function<ExternalIdentity,Optional<ExternalUserIdentity>> reader;
    public GetCurrentBindingV3(Function<ExternalIdentity,Optional<ExternalUserIdentity>> reader) { this.reader=Objects.requireNonNull(reader); }
    public Answer get(ExternalIdentity authenticatedEui,long minVersion) {
        Objects.requireNonNull(authenticatedEui); if(minVersion<0) throw new IllegalArgumentException("Nonnegative minVersion");
        var state=reader.apply(authenticatedEui); if(state.isEmpty()) return new Answer(Status.ABSENT,null);
        var v=state.get(); if(!authenticatedEui.equals(v.identity())) throw new IllegalStateException("Foreign EUI from reader");
        if(v.version()<minVersion) return new Answer(Status.NOT_READY,null);
        return new Answer(Status.AVAILABLE,new View(v.version(),v.isBound(),v.isBound()?v.bindingToken().value():null));
    }
}
