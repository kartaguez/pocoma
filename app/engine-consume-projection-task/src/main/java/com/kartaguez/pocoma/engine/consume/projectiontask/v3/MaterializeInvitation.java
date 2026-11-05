package com.kartaguez.pocoma.engine.consume.projectiontask.v3;

import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.port.projection.v3.InvitationStore;

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
                        if (!sameIdentity(actual, expected)
                                || actual.version() < expected.version()
                                || actual.version() == expected.version()
                                        && !actual.equals(expected))
                            throw new IllegalStateException("Invitation materialization differs");
                    }
                });
    }

    private static boolean sameIdentity(
            com.kartaguez.pocoma.domain.pot.v3.Invitation a,
            com.kartaguez.pocoma.domain.pot.v3.Invitation b) {
        return a.invitationId().equals(b.invitationId())
                && a.creationRequestId().equals(b.creationRequestId())
                && a.creatorIssuer().equals(b.creatorIssuer())
                && a.creatorSubject().equals(b.creatorSubject())
                && a.potId().equals(b.potId())
                && a.tokenHash().equals(b.tokenHash());
    }

    public Recovery prepareRecovery(UUID event, PreparationReads reads) {
        var expected = reads.source(c -> source.load(event, c));
        return c -> {
            var actual = store.byRequest(expected.creationRequestId());
            if (actual.isEmpty() || actual.get().version() < expected.version())
                return EffectEvidence.MISSING;
            return sameIdentity(actual.get(), expected)
                            && (actual.get().version() > expected.version()
                                    || actual.get().equals(expected))
                    ? EffectEvidence.SATISFIED
                    : EffectEvidence.DIVERGENT;
        };
    }
}
