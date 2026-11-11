package com.kartaguez.pocoma.domain.projection.v3;

import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.identity.v3.ExternalUserIdentity;

import java.util.*;

public record CommandTerminalResult(
        UUID requestId,
        String operation,
        ExternalIdentity requesterEui,
        UUID potId,
        String writeOutcome,
        Long resultingPotVersion,
        String rejection) {
    public CommandTerminalResult {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(potId);
        Objects.requireNonNull(operation);
        Objects.requireNonNull(writeOutcome);
        ExternalUserIdentity.compareKeys(requesterEui, requesterEui);
        if (operation.isBlank()
                || !Set.of("CHANGED", "UNCHANGED", "REJECTED", "INVITATION_CREATED")
                        .contains(writeOutcome))
            throw new IllegalArgumentException("Invalid Command result");
        if (writeOutcome.equals("INVITATION_CREATED")) {
            if (!operation.equals("CreateInvitation")
                    || resultingPotVersion != null
                    || rejection != null)
                throw new IllegalArgumentException("Invalid Invitation result");
        } else if (operation.equals("CreateInvitation") && !writeOutcome.equals("REJECTED"))
            throw new IllegalArgumentException("Invalid Invitation result");
        else if (writeOutcome.equals("REJECTED")
                ? rejection == null || rejection.isBlank() || resultingPotVersion != null
                : rejection != null || resultingPotVersion == null || resultingPotVersion < 1)
            throw new IllegalArgumentException("Invalid Command terminal shape");
    }

    public String outcome() {
        return writeOutcome.equals("REJECTED") ? "REJECTED" : "COMPLETED";
    }
}
