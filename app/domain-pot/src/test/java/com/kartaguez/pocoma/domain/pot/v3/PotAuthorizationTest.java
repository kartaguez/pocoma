package com.kartaguez.pocoma.domain.pot.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.pot.value.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class PotAuthorizationTest {
    final PotTransitionsTest f = new PotTransitionsTest();

    @Test
    void d07EveryMutationRoleAndPotLifecycleCell() {
        for (int role = 0; role < 3; role++)
            for (boolean deleted : List.of(false, true)) {
                var pot = f.pot(20, role == 0 ? f.creator : f.actor, role == 1, deleted);
                var results =
                        List.of(
                                PotTransitions.updateDetails(
                                        pot,
                                        20,
                                        new Versioned<>(
                                                1, 1, new PotDetails(f.p, f.label, f.creator)),
                                        new Label("B")),
                                ShareholderTransitions.create(
                                        pot,
                                        20,
                                        List.of(
                                                new ShareholderTransitions.Creation(
                                                        f.a,
                                                        new Name("A"),
                                                        Optional.empty(),
                                                        f.one)),
                                        Map.of(f.a, f.absent(f.a, 20))),
                                ShareholderTransitions.updateWeights(
                                        pot,
                                        20,
                                        List.of(
                                                new ShareholderTransitions.WeightIntent(
                                                        f.a, f.two)),
                                        f.refs(20),
                                        Map.of(
                                                f.a,
                                                new Versioned<>(
                                                        1,
                                                        2,
                                                        new ShareholderWeight(f.p, f.a, f.one)))),
                                ShareholderTransitions.updateDetails(
                                        pot,
                                        20,
                                        List.of(
                                                new ShareholderTransitions.DetailsIntent(
                                                        f.a, new Name("new"), Optional.empty())),
                                        f.refs(20),
                                        Map.of(
                                                f.a,
                                                new Versioned<>(
                                                        1,
                                                        2,
                                                        new ShareholderDetails(
                                                                f.p,
                                                                f.a,
                                                                new Name("A"),
                                                                Optional.empty())))),
                                ExpenseTransitions.create(
                                        pot,
                                        20,
                                        new ExpenseDetails(f.p, f.e, f.label, f.date),
                                        f.financials(100),
                                        false,
                                        f.refs(20)),
                                ExpenseTransitions.updateDetails(
                                        pot, 20, f.eh(), f.ed(10), new Label("new"), f.date),
                                ExpenseTransitions.updateFinancials(
                                        pot, 20, f.eh(), f.ef(10), f.financials(101), f.refs(20)),
                                ExpenseTransitions.delete(
                                        pot, 20, new ExpenseState(f.eh(), f.ed(10), f.ef(10))));
                for (var result : results)
                    assertEquals(
                            !deleted && role < 2,
                            result instanceof TransitionResult.Changed<?>,
                            "role=" + role + " deleted=" + deleted + " " + result);
                var restricted =
                        List.of(
                                PotTransitions.delete(pot, 20),
                                ShareholderTransitions.delete(
                                        pot,
                                        20,
                                        f.ref(f.a, 20, 2, false),
                                        new ActiveShareholderUsage(f.p, 20, f.a, false, true)));
                for (var result : restricted)
                    assertEquals(
                            !deleted && role == 0, result instanceof TransitionResult.Changed<?>);
            }
    }

    @Test
    void d08AttachmentDetachmentAndGeneralMultipleAttachment() {
        for (int role = 0; role < 3; role++)
            for (var old :
                    List.of(
                            Optional.<UserId>empty(),
                            Optional.of(f.creator),
                            Optional.of(f.actor),
                            Optional.of(f.third)))
                for (var next :
                        List.of(
                                Optional.<UserId>empty(),
                                Optional.of(f.creator),
                                Optional.of(f.actor),
                                Optional.of(f.third))) {
                    var actor = role == 0 ? f.creator : f.actor;
                    var pot = f.pot(20, actor, role == 1, false);
                    var current =
                            new Versioned<>(
                                    1, 2, new ShareholderDetails(f.p, f.a, new Name("A"), old));
                    var result =
                            ShareholderTransitions.updateDetails(
                                    pot,
                                    20,
                                    List.of(
                                            new ShareholderTransitions.DetailsIntent(
                                                    f.a, new Name("A"), next)),
                                    f.refs(20),
                                    Map.of(f.a, current));
                    boolean allowed =
                            role < 2
                                    && (old.equals(next)
                                            || (old.isEmpty() && next.equals(Optional.of(actor)))
                                            || (next.isEmpty()
                                                    && (role == 0
                                                            || old.equals(Optional.of(actor)))));
                    assertEquals(
                            allowed,
                            !(result instanceof TransitionResult.Rejected<?>),
                            "role=" + role + " " + old + " -> " + next);
                    assertEquals(old, current.value().userId());
                }
        for (var actor : List.of(f.creator, f.actor)) {
            var pot = f.pot(20, actor, true, false);
            var result =
                    ShareholderTransitions.create(
                            pot,
                            20,
                            List.of(
                                    new ShareholderTransitions.Creation(
                                            f.a, new Name("A"), Optional.of(actor), f.one),
                                    new ShareholderTransitions.Creation(
                                            f.b, new Name("B"), Optional.of(actor), f.one)),
                            Map.of(f.a, f.absent(f.a, 20), f.b, f.absent(f.b, 20)));
            assertInstanceOf(TransitionResult.Changed.class, result);
            assertInstanceOf(
                    TransitionResult.Rejected.class,
                    ShareholderTransitions.create(
                            pot,
                            20,
                            List.of(
                                    new ShareholderTransitions.Creation(
                                            f.c, new Name("C"), Optional.of(f.third), f.one)),
                            Map.of(f.c, f.absent(f.c, 20))));
        }
    }

    @Test
    void d09InvitationEligibilityAndDeletedTargets() {
        for (boolean attached : List.of(false, true))
            for (boolean potDeleted : List.of(false, true))
                for (boolean targetDeleted : List.of(false, true))
                    for (boolean free : List.of(false, true)) {
                        var pot = f.pot(20, f.actor, attached, potDeleted);
                        var header = f.ref(f.a, 20, 2, targetDeleted).header().orElseThrow();
                        var details =
                                new Versioned<>(
                                        1,
                                        2,
                                        new ShareholderDetails(
                                                f.p,
                                                f.a,
                                                new Name("A"),
                                                free ? Optional.empty() : Optional.of(f.third)));
                        assertEquals(
                                !attached && !potDeleted && !targetDeleted && free,
                                ShareholderTransitions.invitationEligible(pot, header, details));
                    }
        var deleted = Map.of(f.a, f.ref(f.a, 20, 2, true));
        assertInstanceOf(
                TransitionResult.Rejected.class,
                ShareholderTransitions.updateDetails(
                        f.pot(20),
                        20,
                        List.of(
                                new ShareholderTransitions.DetailsIntent(
                                        f.a, new Name("B"), Optional.empty())),
                        deleted,
                        Map.of(
                                f.a,
                                new Versioned<>(
                                        1,
                                        2,
                                        new ShareholderDetails(
                                                f.p, f.a, new Name("A"), Optional.empty())))));
        assertInstanceOf(
                TransitionResult.Rejected.class,
                ShareholderTransitions.updateWeights(
                        f.pot(20),
                        20,
                        List.of(new ShareholderTransitions.WeightIntent(f.a, f.two)),
                        deleted,
                        Map.of(
                                f.a,
                                new Versioned<>(1, 2, new ShareholderWeight(f.p, f.a, f.one)))));
    }
}
