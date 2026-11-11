package com.kartaguez.pocoma.domain.projection.v3;

import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.identity.v3.ExternalUserIdentity;
import java.util.*;
public record IdentityTerminalResult(UUID requestId,String operation,ExternalIdentity requesterEui,
                                     String writeOutcome,ExternalIdentity targetEui,Long targetVersion,String rejection) {
    public IdentityTerminalResult {
        Objects.requireNonNull(requestId); Objects.requireNonNull(writeOutcome); Objects.requireNonNull(operation);
        ExternalUserIdentity.compareKeys(requesterEui,requesterEui);
        if(!Set.of("REGISTRATION","LINK","UNLINK").contains(operation) || !Set.of("CHANGED","REJECTED").contains(writeOutcome)) throw new IllegalArgumentException("Invalid Identity result");
        if(writeOutcome.equals("REJECTED") ? rejection==null || rejection.isBlank() || targetEui!=null || targetVersion!=null : rejection!=null || targetEui==null || targetVersion==null || targetVersion<1) throw new IllegalArgumentException("Invalid Identity terminal shape");
        if(targetEui!=null) ExternalUserIdentity.compareKeys(targetEui,targetEui);
        if(operation.equals("REGISTRATION") && targetEui!=null && !targetEui.equals(requesterEui)) throw new IllegalArgumentException("Registration targets requester");
    }
    public String outcome() { return writeOutcome.equals("REJECTED")?"REJECTED":"COMPLETED"; }
}
