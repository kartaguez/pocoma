package com.kartaguez.pocoma.engine.read.commandresult.v3;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.v3.ExternalUserIdentity;
import java.util.*;
public record CommandTerminalResult(UUID requestId,String operation,ExternalIdentity requesterEui,
                                    UUID potId,String writeOutcome,Long resultingPotVersion,String rejection) {
    public CommandTerminalResult {
        Objects.requireNonNull(requestId); Objects.requireNonNull(potId); Objects.requireNonNull(operation); Objects.requireNonNull(writeOutcome);
        ExternalUserIdentity.compareKeys(requesterEui,requesterEui);
        if(operation.isBlank() || !Set.of("CHANGED","UNCHANGED","REJECTED").contains(writeOutcome)) throw new IllegalArgumentException("Invalid Command result");
        if(writeOutcome.equals("REJECTED") ? rejection==null || rejection.isBlank() || resultingPotVersion!=null : rejection!=null || resultingPotVersion==null || resultingPotVersion<1) throw new IllegalArgumentException("Invalid Command terminal shape");
    }
    public String outcome() { return writeOutcome.equals("REJECTED")?"REJECTED":"COMPLETED"; }
}
