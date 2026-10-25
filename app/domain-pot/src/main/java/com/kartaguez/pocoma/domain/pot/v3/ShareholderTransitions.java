package com.kartaguez.pocoma.domain.pot.v3;

import java.util.*;
import java.time.LocalDate;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;

public final class ShareholderTransitions {
    private ShareholderTransitions() {}
    public record Creation(ShareholderId id, Name name, Optional<UserId> userId, Weight defaultWeight) {
        public Creation { Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(userId); Objects.requireNonNull(defaultWeight); }
    }
    public record WeightIntent(ShareholderId id, Weight weight) {
        public WeightIntent { Objects.requireNonNull(id); Objects.requireNonNull(weight); }
    }
    public record DetailsIntent(ShareholderId id, Name name, Optional<UserId> userId) {
        public DetailsIntent { Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(userId); }
    }
    public static TransitionResult<List<ShareholderState>> create(PotFacts pot, long observed, List<Creation> intentions, Map<ShareholderId,ShareholderReference> existence) {
        var targets = TransitionChecks.unique(intentions, Creation::id);
        if (targets.isEmpty()) throw new IllegalArgumentException("Targets required");
        for (var id : targets.keySet()) TransitionChecks.reference(pot, id, existence.get(id));
        var rejection = TransitionChecks.permission(pot, observed, false);
        if (rejection != null) return TransitionChecks.reject(rejection);
        for (var target : targets.values()) {
            if (existence.get(target.id()).header().isPresent()) return TransitionChecks.reject(TransitionResult.Rejection.ALREADY_EXISTS);
            if (target.userId().isPresent() && !target.userId().get().equals(pot.authorization().actorUserId())) return TransitionChecks.reject(TransitionResult.Rejection.INVALID_ATTACHMENT);
        }
        long v = TransitionChecks.candidate(pot);
        var states = targets.values().stream().map(t -> new ShareholderState(
                new Versioned<>(1,v,new ShareholderHeader(pot.potId(),t.id(),false)),
                new Versioned<>(1,v,new ShareholderDetails(pot.potId(),t.id(),t.name(),t.userId())),
                new Versioned<>(1,v,new ShareholderWeight(pot.potId(),t.id(),t.defaultWeight())))).toList();
        return TransitionResult.Changed.propose(pot.workingVersion(),states);
    }
    public static TransitionResult<Map<ShareholderId,Versioned<ShareholderWeight>>> updateWeights(PotFacts pot, long observed,
            List<WeightIntent> intentions, Map<ShareholderId,ShareholderReference> references, Map<ShareholderId,Versioned<ShareholderWeight>> current) {
        var targets = TransitionChecks.unique(intentions, WeightIntent::id);
        if (targets.isEmpty()) throw new IllegalArgumentException("Targets required");
        for (var id : targets.keySet()) {
            TransitionChecks.reference(pot,id,references.get(id));
            if (references.get(id).header().isPresent()) {
                var value = current.get(id);
                if (value == null || !id.equals(value.value().shareholderId())) throw new InconsistentPotFactsException("Missing weight");
                TransitionChecks.revision(pot,value.value().potId(),value);
            }
        }
        var rejection = TransitionChecks.permission(pot,observed,false);
        if (rejection != null) return TransitionChecks.reject(rejection);
        for (var id : targets.keySet()) {
            rejection = TransitionChecks.activeReference(references.get(id),observed);
            if (rejection != null) return TransitionChecks.reject(rejection);
            if (current.get(id).activeFrom() > observed) return TransitionChecks.reject(TransitionResult.Rejection.OBSOLETE);
        }
        var changes = new LinkedHashMap<ShareholderId,Versioned<ShareholderWeight>>();
        for (var t : targets.values()) if (!t.weight().equals(current.get(t.id()).value().defaultWeight())) {
            var value = new ShareholderWeight(pot.potId(),t.id(),t.weight());
            changes.put(t.id(),TransitionChecks.next(current.get(t.id()),value,TransitionChecks.candidate(pot)));
        }
        return changes.isEmpty() ? new TransitionResult.Unchanged<>(pot.workingVersion()) : TransitionResult.Changed.propose(pot.workingVersion(),Map.copyOf(changes));
    }
    public static TransitionResult<Map<ShareholderId,Versioned<ShareholderDetails>>> updateDetails(PotFacts pot, long observed,
            List<DetailsIntent> intentions, Map<ShareholderId,ShareholderReference> references, Map<ShareholderId,Versioned<ShareholderDetails>> current) {
        var targets = TransitionChecks.unique(intentions, DetailsIntent::id);
        if (targets.isEmpty()) throw new IllegalArgumentException("Targets required");
        for (var id : targets.keySet()) {
            TransitionChecks.reference(pot,id,references.get(id));
            if (references.get(id).header().isPresent()) {
                var value = current.get(id);
                if (value == null || !id.equals(value.value().shareholderId())) throw new InconsistentPotFactsException("Missing details");
                TransitionChecks.revision(pot,value.value().potId(),value);
            }
        }
        var rejection = TransitionChecks.permission(pot,observed,false);
        if (rejection != null) return TransitionChecks.reject(rejection);
        for (var t : targets.values()) {
            rejection = TransitionChecks.activeReference(references.get(t.id()),observed);
            if (rejection != null) return TransitionChecks.reject(rejection);
            var old = current.get(t.id());
            if (old.activeFrom() > observed) return TransitionChecks.reject(TransitionResult.Rejection.OBSOLETE);
            if (!attachmentAllowed(pot.authorization(),old.value().userId(),t.userId())) return TransitionChecks.reject(TransitionResult.Rejection.INVALID_ATTACHMENT);
        }
        var changes = new LinkedHashMap<ShareholderId,Versioned<ShareholderDetails>>();
        for (var t : targets.values()) {
            var old = current.get(t.id());
            var value = new ShareholderDetails(pot.potId(),t.id(),t.name(),t.userId());
            if (!old.value().equals(value)) changes.put(t.id(),TransitionChecks.next(old,value,TransitionChecks.candidate(pot)));
        }
        return changes.isEmpty() ? new TransitionResult.Unchanged<>(pot.workingVersion()) : TransitionResult.Changed.propose(pot.workingVersion(),Map.copyOf(changes));
    }
    private static boolean attachmentAllowed(AuthorizationFacts actor, Optional<UserId> old, Optional<UserId> requested) {
        if (old.equals(requested)) return true;
        if (old.isEmpty()) return requested.orElseThrow().equals(actor.actorUserId());
        return requested.isEmpty() && (actor.creator() || old.get().equals(actor.actorUserId()));
    }
    public static TransitionResult<Versioned<ShareholderHeader>> delete(PotFacts pot, long observed, ShareholderReference target, ActiveShareholderUsage usage) {
        TransitionChecks.reference(pot,target.shareholderId(),target);
        if (!usage.potId().equals(pot.potId()) || usage.workingVersion()!=pot.workingVersion() || !usage.shareholderId().equals(target.shareholderId())) throw new InconsistentPotFactsException("Mixed usage facts");
        var rejection = TransitionChecks.permission(pot,observed,true);
        if (rejection == null) rejection = TransitionChecks.activeReference(target,pot.workingVersion());
        if (rejection != null) return TransitionChecks.reject(rejection);
        if (usage.referenced()) return TransitionChecks.reject(TransitionResult.Rejection.REFERENCED);
        var header = target.header().orElseThrow();
        return TransitionResult.Changed.propose(pot.workingVersion(),TransitionChecks.next(header,new ShareholderHeader(pot.potId(),target.shareholderId(),true),TransitionChecks.candidate(pot)));
    }
}
