package com.kartaguez.pocoma.domain.projection;

import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.identity.ExternalUserIdentity;

import java.util.*;

public record CommandTerminalResult(
        UUID requestId,
        String operation,
        ExternalIdentity requesterEui,
        UUID potId,
        String writeOutcome,
        Long resultingPotVersion,
        String rejection,
        CreatedResources createdResources) {
    public record CreatedShareholder(int inputIndex, UUID shareholderId) {
        public CreatedShareholder {
            if (inputIndex < 0) throw new IllegalArgumentException("Negative input index");
            Objects.requireNonNull(shareholderId);
        }
    }

    public record CreatedResources(
            UUID potId, UUID expenseId, List<CreatedShareholder> shareholders) {
        public CreatedResources {
            if (shareholders != null) {
                if (shareholders.isEmpty()) {
                    throw new IllegalArgumentException("Empty created shareholders");
                }
                var seen = new HashSet<UUID>();
                for (int i = 0; i < shareholders.size(); i++) {
                    var item = Objects.requireNonNull(shareholders.get(i));
                    if (item.inputIndex() != i || !seen.add(item.shareholderId())) {
                        throw new IllegalArgumentException("Invalid created shareholder ordering");
                    }
                }
                shareholders = List.copyOf(shareholders);
            }
            int populated =
                    (potId != null ? 1 : 0)
                            + (expenseId != null ? 1 : 0)
                            + (shareholders != null ? 1 : 0);
            if (populated != 1) {
                throw new IllegalArgumentException("Exactly one created resource family required");
            }
        }

        public static CreatedResources pot(UUID potId) {
            return new CreatedResources(Objects.requireNonNull(potId), null, null);
        }

        public static CreatedResources expense(UUID expenseId) {
            return new CreatedResources(null, Objects.requireNonNull(expenseId), null);
        }

        public static CreatedResources shareholders(List<CreatedShareholder> shareholders) {
            return new CreatedResources(null, null, Objects.requireNonNull(shareholders));
        }
    }

    /** Historical constructor without createdResources. */
    public CommandTerminalResult(
            UUID requestId,
            String operation,
            ExternalIdentity requesterEui,
            UUID potId,
            String writeOutcome,
            Long resultingPotVersion,
            String rejection) {
        this(requestId, operation, requesterEui, potId, writeOutcome, resultingPotVersion, rejection, null);
    }

    public CommandTerminalResult {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(operation);
        Objects.requireNonNull(writeOutcome);
        ExternalUserIdentity.compareKeys(requesterEui, requesterEui);
        if (operation.isBlank()
                || !Set.of("CHANGED", "UNCHANGED", "REJECTED", "INVITATION_CREATED")
                        .contains(writeOutcome))
            throw new IllegalArgumentException("Invalid Command result");
        if (potId == null && !(operation.equals("CreatePot") && writeOutcome.equals("REJECTED"))) {
            throw new NullPointerException("PotId required");
        }
        if (createdResources != null) {
            if (!writeOutcome.equals("CHANGED")) {
                throw new IllegalArgumentException("CreatedResources only allowed on CHANGED");
            }
            if (createdResources.potId() != null && !createdResources.potId().equals(potId)) {
                throw new IllegalArgumentException("Created PotId mismatch");
            }
        }
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
