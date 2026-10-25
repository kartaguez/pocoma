package com.kartaguez.pocoma.domain.pot.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class InvitationAttachmentTest {
    final PotId p = new PotId(UUID.randomUUID());
    final ShareholderId id = new ShareholderId(UUID.randomUUID());
    final UserId creator = new UserId(UUID.randomUUID()), guest = new UserId(UUID.randomUUID());
    final AttachShareholderViaInvitation intent = new AttachShareholderViaInvitation(id);
    final Versioned<ShareholderHeader> header =
            new Versioned<>(1, 2, new ShareholderHeader(p, id, false));
    final Versioned<ShareholderDetails> free =
            new Versioned<>(1, 2, new ShareholderDetails(p, id, new Name("A"), Optional.empty()));

    PotFacts facts(UserId actor, boolean attached, boolean deleted) {
        return new PotFacts(
                p,
                4,
                new Versioned<>(1, 1, new PotHeader(p, deleted)),
                new AuthorizationFacts(p, 4, creator, actor, attached));
    }

    ShareholderReference reference(Versioned<ShareholderHeader> h) {
        return new ShareholderReference(p, 4, id, h == null ? 0 : 2, Optional.ofNullable(h));
    }

    void rejects(TransitionResult<?> result, TransitionResult.Rejection reason) {
        assertEquals(reason, assertInstanceOf(TransitionResult.Rejected.class, result).reason());
    }

    @Test
    void outsiderAndUnattachedCreatorAttachOnlyThemselvesWithOneDetailsRevision() {
        for (var user : List.of(guest, creator)) {
            var facts = facts(user, false, false);
            assertEquals(user.equals(creator), facts.authorization().canMutate());
            var result =
                    assertInstanceOf(
                            TransitionResult.Changed.class,
                            intent.decide(facts, reference(header), free));
            assertEquals(4, result.expectedPotVersion());
            assertEquals(5, result.candidatePotVersion());
            var next = (Versioned<?>) ((Map<?, ?>) result.changes()).get(id);
            assertEquals(2, next.localVersion());
            assertEquals(5, next.activeFrom());
            assertEquals(Optional.of(user), ((ShareholderDetails) next.value()).userId());
            assertEquals(free.value().name(), ((ShareholderDetails) next.value()).name());
        }
    }

    @Test
    void occupiedDeletedAbsentAndAlreadyAttachedRemainRejected() {
        rejects(
                intent.decide(facts(guest, true, false), reference(header), free),
                TransitionResult.Rejection.INVALID_ATTACHMENT);
        var occupied =
                new Versioned<>(
                        2, 3, new ShareholderDetails(p, id, new Name("A"), Optional.of(creator)));
        rejects(
                intent.decide(facts(guest, false, false), reference(header), occupied),
                TransitionResult.Rejection.INVALID_ATTACHMENT);
        rejects(
                intent.decide(facts(guest, false, true), reference(header), free),
                TransitionResult.Rejection.DELETED);
        rejects(
                intent.decide(facts(guest, false, false), reference(null), null),
                TransitionResult.Rejection.NOT_FOUND);
        var deleted = new Versioned<>(2, 3, new ShareholderHeader(p, id, true));
        rejects(
                intent.decide(facts(guest, false, false), reference(deleted), free),
                TransitionResult.Rejection.DELETED);
    }

    @Test
    void mixedOrMissingDurableFactsAreTechnicalAnomalies() {
        assertThrows(
                InconsistentPotFactsException.class,
                () -> intent.decide(facts(guest, false, false), reference(header), null));
        var mixed =
                new Versioned<>(
                        1,
                        2,
                        new ShareholderDetails(
                                p,
                                new ShareholderId(UUID.randomUUID()),
                                new Name("B"),
                                Optional.empty()));
        assertThrows(
                InconsistentPotFactsException.class,
                () -> intent.decide(facts(guest, false, false), reference(header), mixed));
    }
}
