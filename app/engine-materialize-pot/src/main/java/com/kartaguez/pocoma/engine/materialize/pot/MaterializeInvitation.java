package com.kartaguez.pocoma.engine.materialize.pot;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;

import com.kartaguez.pocoma.port.projection.InvitationStore;

import java.util.*;

/** Independent Invitation participant; no decryption capability. */
public final class MaterializeInvitation implements ConsumptionParticipant {
    private final InvitationSource source;
    private final InvitationStore store;

    public MaterializeInvitation(InvitationSource source, InvitationStore store) {
        this.source = Objects.requireNonNull(source);
        this.store = Objects.requireNonNull(store);
    }

    public Optional<Prepared> prepare(UUID event, PreparationReads reads) {
        var expected = reads.source(c -> source.load(event, c));
        return Optional.of(
                new Prepared() {
                    public void finalizeIn(SlotCompletion completion) {
                        store.put(expected);
                        completion.complete();
                        store.closePublication();
                    }

                    public void requireCompletionReady() {
                        var actual = store.byRequest(expected.creationRequestId()).orElseThrow();
                        if (!matchesExpected(actual, expected)) {
                            throw new IllegalStateException("Invitation materialization differs");
                        }
                    }
                });
    }

    private static boolean matchesExpected(
            com.kartaguez.pocoma.domain.pot.Invitation actual,
            com.kartaguez.pocoma.domain.pot.Invitation expected) {
        if (!sameIdentity(actual, expected)) return false;
        if (actual.version() > expected.version()) return true;
        return actual.version() == expected.version() && actual.equals(expected);
    }

    private static boolean sameIdentity(
            com.kartaguez.pocoma.domain.pot.Invitation a,
            com.kartaguez.pocoma.domain.pot.Invitation b) {
        if (!a.invitationId().equals(b.invitationId())) return false;
        if (!a.creationRequestId().equals(b.creationRequestId())) return false;
        if (!a.creatorIssuer().equals(b.creatorIssuer())) return false;
        if (!a.creatorSubject().equals(b.creatorSubject())) return false;
        return a.potId().equals(b.potId()) && a.tokenHash().equals(b.tokenHash());
    }

    public Recovery prepareRecovery(UUID event, PreparationReads reads) {
        var expected = reads.source(c -> source.load(event, c));
        return c -> {
            var actual = store.byRequest(expected.creationRequestId());
            if (actual.isEmpty() || actual.get().version() < expected.version()) {
                return EffectEvidence.MISSING;
            }
            return matchesExpected(actual.get(), expected)
                    ? EffectEvidence.SATISFIED
                    : EffectEvidence.DIVERGENT;
        };
    }
}
