package com.kartaguez.pocoma.domain.pot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import java.util.function.BiFunction;
import java.util.function.Function;

import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

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
    private record UpdateContext(
            PotFacts pot,
            long observed,
            Map<ShareholderId, ShareholderReference> references) {
        void validateReference(ShareholderId id) {
            TransitionChecks.reference(pot, id, references.get(id));
        }

        TransitionResult.Rejection checkActive(ShareholderId id, Versioned<?> version) {
            var rejection = TransitionChecks.activeReference(references.get(id), observed);
            if (rejection != null) return rejection;
            return version.activeFrom() > observed ? TransitionResult.Rejection.OBSOLETE : null;
        }
    }

    public static TransitionResult<List<ShareholderState>> create(
            PotFacts pot,
            long observed,
            List<Creation> intentions,
            Map<ShareholderId, ShareholderReference> existence) {
        var targets = TransitionChecks.unique(intentions, Creation::id);
        if (targets.isEmpty()) throw new IllegalArgumentException("Targets required");
        targets.keySet().forEach(id -> TransitionChecks.reference(pot, id, existence.get(id)));
        var rejection = TransitionChecks.permission(pot, observed, false);
        if (rejection != null) return TransitionChecks.reject(rejection);
        var creationRejection = findCreationRejection(pot, targets, existence);
        if (creationRejection != null) return TransitionChecks.reject(creationRejection);
        return TransitionResult.Changed.propose(pot.workingVersion(), buildCreatedStates(pot, targets));
    }

    private static TransitionResult.Rejection findCreationRejection(
            PotFacts pot,
            Map<ShareholderId, Creation> targets,
            Map<ShareholderId, ShareholderReference> existence) {
        for (var target : targets.values()) {
            if (existence.get(target.id()).header().isPresent()) {
                return TransitionResult.Rejection.ALREADY_EXISTS;
            }
            if (isInvalidAttachment(pot, target)) {
                return TransitionResult.Rejection.INVALID_ATTACHMENT;
            }
        }
        return null;
    }

    private static boolean isInvalidAttachment(PotFacts pot, Creation target) {
        return target.userId().isPresent()
                && !target.userId().get().equals(pot.authorization().actorUserId());
    }

    private static List<ShareholderState> buildCreatedStates(
            PotFacts pot, Map<ShareholderId, Creation> targets) {
        long v = TransitionChecks.candidate(pot);
        return targets.values().stream()
                .map(
                        t ->
                                new ShareholderState(
                                        new Versioned<>(
                                                1, v, new ShareholderHeader(pot.potId(), t.id(), false)),
                                        new Versioned<>(
                                                1,
                                                v,
                                                new ShareholderDetails(
                                                        pot.potId(), t.id(), t.name(), t.userId())),
                                        new Versioned<>(
                                                1,
                                                v,
                                                new ShareholderWeight(
                                                        pot.potId(), t.id(), t.defaultWeight()))))
                .toList();
    }

    private record VersionMetadata<V>(
            Function<V, ShareholderId> shareholderIdOf,
            Function<V, PotId> potIdOf,
            String missingMessage) {}

    private static final VersionMetadata<ShareholderWeight> WEIGHT_METADATA =
            new VersionMetadata<>(
                    ShareholderWeight::shareholderId, ShareholderWeight::potId, "Missing weight");

    private static final VersionMetadata<ShareholderDetails> DETAILS_METADATA =
            new VersionMetadata<>(
                    ShareholderDetails::shareholderId, ShareholderDetails::potId, "Missing details");

    public static TransitionResult<Map<ShareholderId, Versioned<ShareholderWeight>>> updateWeights(
            PotFacts pot,
            long observed,
            List<WeightIntent> intentions,
            Map<ShareholderId, ShareholderReference> references,
            Map<ShareholderId, Versioned<ShareholderWeight>> current) {
        var targets = TransitionChecks.unique(intentions, WeightIntent::id);
        if (targets.isEmpty()) throw new IllegalArgumentException("Targets required");
        var ctx = new UpdateContext(pot, observed, references);
        validatePresentVersions(ctx, targets.keySet(), current, WEIGHT_METADATA);
        var rejection = findUpdateRejection(ctx, targets.keySet(), current);
        if (rejection != null) return TransitionChecks.reject(rejection);
        var changes =
                collectChanges(
                        pot,
                        targets,
                        current,
                        (intent, old) -> nextWeightValue(pot, intent, old));
        return toUpdateResult(pot, changes);
    }

    private static Optional<ShareholderWeight> nextWeightValue(
            PotFacts pot, WeightIntent intent, Versioned<ShareholderWeight> old) {
        if (intent.weight().equals(old.value().defaultWeight())) return Optional.empty();
        return Optional.of(new ShareholderWeight(pot.potId(), intent.id(), intent.weight()));
    }

    public static TransitionResult<Map<ShareholderId, Versioned<ShareholderDetails>>> updateDetails(
            PotFacts pot,
            long observed,
            List<DetailsIntent> intentions,
            Map<ShareholderId, ShareholderReference> references,
            Map<ShareholderId, Versioned<ShareholderDetails>> current) {
        var targets = TransitionChecks.unique(intentions, DetailsIntent::id);
        if (targets.isEmpty()) throw new IllegalArgumentException("Targets required");
        var ctx = new UpdateContext(pot, observed, references);
        validatePresentVersions(ctx, targets.keySet(), current, DETAILS_METADATA);
        var rejection = findDetailsRejection(ctx, targets, current);
        if (rejection != null) return TransitionChecks.reject(rejection);
        var changes =
                collectChanges(
                        pot,
                        targets,
                        current,
                        (intent, old) -> nextDetailsValue(pot, intent, old));
        return toUpdateResult(pot, changes);
    }

    private static Optional<ShareholderDetails> nextDetailsValue(
            PotFacts pot, DetailsIntent intent, Versioned<ShareholderDetails> old) {
        var value = new ShareholderDetails(pot.potId(), intent.id(), intent.name(), intent.userId());
        return old.value().equals(value) ? Optional.empty() : Optional.of(value);
    }

    private static <V> void validatePresentVersions(
            UpdateContext ctx,
            java.util.Set<ShareholderId> ids,
            Map<ShareholderId, Versioned<V>> current,
            VersionMetadata<V> metadata) {
        for (var id : ids) {
            ctx.validateReference(id);
            if (!ctx.references().get(id).header().isPresent()) continue;
            var value = current.get(id);
            if (value == null || !id.equals(metadata.shareholderIdOf().apply(value.value()))) {
                throw new InconsistentPotFactsException(metadata.missingMessage());
            }
            TransitionChecks.revision(ctx.pot(), metadata.potIdOf().apply(value.value()), value);
        }
    }

    private static TransitionResult.Rejection findDetailsRejection(
            UpdateContext ctx,
            Map<ShareholderId, DetailsIntent> targets,
            Map<ShareholderId, Versioned<ShareholderDetails>> current) {
        var rejection = findUpdateRejection(ctx, targets.keySet(), current);
        if (rejection != null) return rejection;
        for (var t : targets.values()) {
            var old = current.get(t.id());
            if (!attachmentAllowed(ctx.pot().authorization(), old.value().userId(), t.userId())) {
                return TransitionResult.Rejection.INVALID_ATTACHMENT;
            }
        }
        return null;
    }

    private static TransitionResult.Rejection findUpdateRejection(
            UpdateContext ctx,
            java.util.Set<ShareholderId> ids,
            Map<ShareholderId, ? extends Versioned<?>> current) {
        var permissionRejection = TransitionChecks.permission(ctx.pot(), ctx.observed(), false);
        if (permissionRejection != null) return permissionRejection;
        for (var id : ids) {
            var rejection = ctx.checkActive(id, current.get(id));
            if (rejection != null) return rejection;
        }
        return null;
    }

    private static <I, V> Map<ShareholderId, Versioned<V>> collectChanges(
            PotFacts pot,
            Map<ShareholderId, I> targets,
            Map<ShareholderId, Versioned<V>> current,
            BiFunction<I, Versioned<V>, Optional<V>> nextValueFactory) {
        var changes = new LinkedHashMap<ShareholderId, Versioned<V>>();
        long nextVersion = TransitionChecks.candidate(pot);
        for (var entry : targets.entrySet()) {
            var old = current.get(entry.getKey());
            nextValueFactory
                    .apply(entry.getValue(), old)
                    .ifPresent(value -> changes.put(entry.getKey(), TransitionChecks.next(old, value, nextVersion)));
        }
        return changes;
    }

    private static <V> TransitionResult<Map<ShareholderId, Versioned<V>>> toUpdateResult(
            PotFacts pot, Map<ShareholderId, Versioned<V>> changes) {
        if (changes.isEmpty()) return new TransitionResult.Unchanged<>(pot.workingVersion());
        return TransitionResult.Changed.propose(pot.workingVersion(), Map.copyOf(changes));
    }

    /** Pure eligibility only; token validity/expiry and atomic execution belong to the Invitation lot. */
    public static boolean invitationEligible(PotFacts pot, Versioned<ShareholderHeader> header,
            Versioned<ShareholderDetails> details) {
        TransitionChecks.revision(pot, header.value().potId(), header);
        TransitionChecks.revision(pot, details.value().potId(), details);
        if (!header.value().shareholderId().equals(details.value().shareholderId()))
            throw new InconsistentPotFactsException("Mixed invitation target");
        return !pot.header().value().deleted() && !header.value().deleted()
                && details.value().userId().isEmpty() && !pot.authorization().activelyAttached();
    }

    private static boolean attachmentAllowed(AuthorizationFacts actor, Optional<UserId> old, Optional<UserId> requested) {
        if (old.equals(requested)) return true;
        if (old.isEmpty()) return requested.orElseThrow().equals(actor.actorUserId());
        return requested.isEmpty() && (actor.creator() || old.get().equals(actor.actorUserId()));
    }
    public static TransitionResult<Versioned<ShareholderHeader>> delete(
            PotFacts pot, long observed, ShareholderReference target, ActiveShareholderUsage usage) {
        TransitionChecks.reference(pot, target.shareholderId(), target);
        if (isMixedUsage(pot, target, usage)) {
            throw new InconsistentPotFactsException("Mixed usage facts");
        }
        var rejection = TransitionChecks.permission(pot, observed, true);
        if (rejection == null) rejection = TransitionChecks.activeReference(target, pot.workingVersion());
        if (rejection != null) return TransitionChecks.reject(rejection);
        if (usage.referenced()) return TransitionChecks.reject(TransitionResult.Rejection.REFERENCED);
        var header = target.header().orElseThrow();
        return TransitionResult.Changed.propose(
                pot.workingVersion(),
                TransitionChecks.next(
                        header,
                        new ShareholderHeader(pot.potId(), target.shareholderId(), true),
                        TransitionChecks.candidate(pot)));
    }

    private static boolean isMixedUsage(
            PotFacts pot, ShareholderReference target, ActiveShareholderUsage usage) {
        if (!usage.potId().equals(pot.potId())) return true;
        if (usage.workingVersion() != pot.workingVersion()) return true;
        return !usage.shareholderId().equals(target.shareholderId());
    }
}
