package com.kartaguez.pocoma.domain.pot.v3;

import java.util.*;
import java.time.LocalDate;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;

final class TransitionChecks {
    private TransitionChecks() {}
    static TransitionResult.Rejection permission(PotFacts pot, long observed, boolean creatorOnly) {
        if (pot.header().value().deleted()) return TransitionResult.Rejection.DELETED;
        if (creatorOnly ? !pot.authorization().creator() : !pot.authorization().canMutate()) return TransitionResult.Rejection.FORBIDDEN;
        return observed < 1 || observed > pot.workingVersion() ? TransitionResult.Rejection.OBSOLETE : null;
    }
    static void revision(PotFacts pot, PotId owner, Versioned<?> revision) {
        if (!pot.potId().equals(owner) || revision.activeFrom() > pot.workingVersion())
            throw new InconsistentPotFactsException("Revision outside working Pot/version");
    }
    static void reference(PotFacts pot, ShareholderId id, ShareholderReference fact) {
        if (fact == null || !id.equals(fact.shareholderId()) || !pot.potId().equals(fact.potId()) || pot.workingVersion() != fact.workingVersion())
            throw new InconsistentPotFactsException("Missing or mixed reference fact");
    }
    static TransitionResult.Rejection activeReference(ShareholderReference fact, long observed) {
        if (fact.header().isEmpty()) return TransitionResult.Rejection.NOT_FOUND;
        if (fact.header().orElseThrow().value().deleted()) return TransitionResult.Rejection.DELETED;
        return fact.createdAt() > observed ? TransitionResult.Rejection.INVALID_REFERENCE : null;
    }
    static <T> Versioned<T> next(Versioned<T> old, T value, long candidate) {
        return new Versioned<>(Math.incrementExact(old.localVersion()), candidate, value);
    }
    static long candidate(PotFacts pot) { return Math.incrementExact(pot.workingVersion()); }
    static <T> TransitionResult<T> reject(TransitionResult.Rejection reason) { return new TransitionResult.Rejected<>(reason); }
    static <K,V> Map<K,V> unique(List<V> values, java.util.function.Function<V,K> key) {
        var result = new LinkedHashMap<K,V>();
        for (var value : values) if (result.putIfAbsent(key.apply(value), value) != null) throw new IllegalArgumentException("Duplicate target");
        return result;
    }
}
