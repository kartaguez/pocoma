package com.kartaguez.pocoma.engine.write.pot.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand.*;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.*;

class PotCommandIntentTest {
    final ShareholderId id = new ShareholderId(UUID.randomUUID());

    <T> void check(
            T first,
            T sameTargetDifferentValue,
            Function<List<T>, Intent> construct,
            Function<Intent, List<T>> targets) {
        assertThrows(IllegalArgumentException.class, () -> construct.apply(null));
        assertThrows(IllegalArgumentException.class, () -> construct.apply(List.of()));
        assertThrows(
                IllegalArgumentException.class, () -> construct.apply(Arrays.asList(first, null)));
        assertThrows(IllegalArgumentException.class, () -> construct.apply(List.of(first, first)));
        assertThrows(
                IllegalArgumentException.class,
                () -> construct.apply(List.of(first, sameTargetDifferentValue)));
        var input = new ArrayList<T>(List.of(first));
        var intention = construct.apply(input);
        input.clear();
        assertEquals(List.of(first), targets.apply(intention));
        assertThrows(UnsupportedOperationException.class, () -> targets.apply(intention).clear());
    }

    @Test
    void additionsValidateStructureAndCopy() {
        check(
                new ShareholderTransitions.Creation(
                        id,
                        new Name("A"),
                        Optional.empty(),
                        new com.kartaguez.pocoma.domain.pot.v3.Weight(Fraction.ONE)),
                new ShareholderTransitions.Creation(
                        id,
                        new Name("B"),
                        Optional.empty(),
                        new com.kartaguez.pocoma.domain.pot.v3.Weight(Fraction.ONE)),
                AddShareholders::new,
                i -> ((AddShareholders) i).targets());
    }

    @Test
    void detailsValidateStructureAndCopy() {
        check(
                new ShareholderTransitions.DetailsIntent(id, new Name("A"), Optional.empty()),
                new ShareholderTransitions.DetailsIntent(id, new Name("B"), Optional.empty()),
                UpdateShareholderDetails::new,
                i -> ((UpdateShareholderDetails) i).targets());
    }

    @Test
    void weightsValidateStructureAndCopy() {
        check(
                new ShareholderTransitions.WeightIntent(
                        id, new com.kartaguez.pocoma.domain.pot.v3.Weight(Fraction.ONE)),
                new ShareholderTransitions.WeightIntent(
                        id, new com.kartaguez.pocoma.domain.pot.v3.Weight(new Fraction(2, 1))),
                UpdateShareholderWeights::new,
                i -> ((UpdateShareholderWeights) i).targets());
    }
}
