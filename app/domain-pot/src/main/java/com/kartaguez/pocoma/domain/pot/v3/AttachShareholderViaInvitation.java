package com.kartaguez.pocoma.domain.pot.v3;

import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Dedicated self-attachment intention. The application must first validate the invitation for this
 * Pot and protect its validity/expiry and the actor's Identity through final WRITE. This pure
 * transition grants no ordinary mutation permission and accepts no user chosen by the caller.
 */
public record AttachShareholderViaInvitation(ShareholderId shareholderId) {
    public AttachShareholderViaInvitation {
        Objects.requireNonNull(shareholderId);
    }

    public TransitionResult<Map<ShareholderId, Versioned<ShareholderDetails>>> decide(
            PotFacts pot, ShareholderReference target, Versioned<ShareholderDetails> details) {
        TransitionChecks.reference(pot, shareholderId, target);
        if (pot.header().value().deleted())
            return TransitionChecks.reject(TransitionResult.Rejection.DELETED);
        if (target.header().isEmpty())
            return TransitionChecks.reject(TransitionResult.Rejection.NOT_FOUND);
        var header = target.header().orElseThrow();
        if (details == null || !shareholderId.equals(details.value().shareholderId()))
            throw new InconsistentPotFactsException("Missing invitation target details");
        TransitionChecks.revision(pot, details.value().potId(), details);
        if (header.value().deleted())
            return TransitionChecks.reject(TransitionResult.Rejection.DELETED);
        if (!ShareholderTransitions.invitationEligible(pot, header, details))
            return TransitionChecks.reject(TransitionResult.Rejection.INVALID_ATTACHMENT);
        var value =
                new ShareholderDetails(
                        pot.potId(),
                        shareholderId,
                        details.value().name(),
                        Optional.of(pot.authorization().actorUserId()));
        return TransitionResult.Changed.propose(
                pot.workingVersion(),
                Map.of(
                        shareholderId,
                        TransitionChecks.next(details, value, TransitionChecks.candidate(pot))));
    }
}
