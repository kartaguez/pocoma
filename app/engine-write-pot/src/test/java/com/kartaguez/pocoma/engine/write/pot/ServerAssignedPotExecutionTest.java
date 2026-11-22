package com.kartaguez.pocoma.engine.write.pot;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.spi.RecoveryObservation;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;
import com.kartaguez.pocoma.domain.pot.*;
import com.kartaguez.pocoma.domain.pot.expense.*;
import com.kartaguez.pocoma.domain.pot.revision.*;
import com.kartaguez.pocoma.domain.pot.shareholder.*;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.PotCommandStore.*;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.*;

import org.junit.jupiter.api.Test;

import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class ServerAssignedPotExecutionTest {
    private final UUID requestId = UUID.randomUUID();
    private final UserId actor = new UserId(UUID.randomUUID());
    private final PotCommand.Eui eui = new PotCommand.Eui("issuer", "subject");

    private static PreparationReads inSnapshot() {
        return new PreparationReads() {
            @Override
            public <T> T source(
                    java.util.function.Function<
                                    com.kartaguez.pocoma.port.transaction.RecoveryReadContext, T>
                            action) {
                return action.apply(() -> {});
            }

            @Override
            public <T> T effects(
                    java.util.function.Function<
                                    com.kartaguez.pocoma.port.transaction.RecoveryReadContext, T>
                            action) {
                return action.apply(() -> {});
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T port(Class<T> type, java.util.function.BiFunction<Method, Object[], Object> fn) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> fn.apply(m, a));
    }

    @Test
    void createPotGeneratesIdAtExecutionAndNotOnInvalidBinding() {
        UUID generatedPot = UUID.fromString("11111111-1111-4111-8111-111111111111");
        var idCalls = new AtomicInteger();
        var command =
                new PotCommand(
                        requestId, eui, "binding-token", null, 0, new PotCommand.CreatePot(new Label("Trip")));
        var captured = new Result[1];
        var protectedPot = new PotId[1];

        var store =
                port(
                        PotCommandStore.class,
                        (m, args) ->
                                switch (m.getName()) {
                                    case "load" -> new Stored(command, Optional.empty());
                                    case "protectPot" -> {
                                        protectedPot[0] = (PotId) args[0];
                                        yield true;
                                    }
                                    case "finishTerminal" -> {
                                        captured[0] = (Result) args[1];
                                        yield null;
                                    }
                                    case "requireCompletionReady", "publish" -> null;
                                    default -> throw new AssertionError(m);
                                });
        var history = port(PotHistoryPort.class, (m, args) -> Optional.empty());
        var revisions = port(PotRevisionPort.class, (m, args) -> null);

        // 1. Invalid binding: no PotId generated, rejected with null potId and null createdResources
        var invalidAuthority =
                new PotActorAuthority() {
                    public Witness observe(PotCommand c) {
                        return new Witness(0, Optional.empty());
                    }

                    public boolean protect(PotCommand c, Witness w) {
                        return true;
                    }
                };
        var invalidEngine =
                new ExecutePotCommand(
                        store,
                        (id, ctx) -> RecoveryObservation.COMPLETED,
                        invalidAuthority,
                        history,
                        revisions,
                        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                        () -> {
                            idCalls.incrementAndGet();
                            return generatedPot;
                        });
        var prepInvalid = invalidEngine.prepare(requestId, inSnapshot()).orElseThrow();
        prepInvalid.finalizeIn(prepInvalid::requireCompletionReady);
        assertEquals(0, idCalls.get());
        assertEquals(Outcome.REJECTED, captured[0].outcome());
        assertEquals("INVALID_BINDING", captured[0].rejection());
        assertNull(captured[0].potId());
        assertNull(captured[0].createdResources());
        assertNull(protectedPot[0]);

        // 2. Valid binding: PotId generated at execution, protected, and returned in Result + CreatedResources
        var validAuthority =
                new PotActorAuthority() {
                    public Witness observe(PotCommand c) {
                        return new Witness(1, Optional.of(actor));
                    }

                    public boolean protect(PotCommand c, Witness w) {
                        return true;
                    }
                };
        var validEngine =
                new ExecutePotCommand(
                        store,
                        (id, ctx) -> RecoveryObservation.COMPLETED,
                        validAuthority,
                        history,
                        revisions,
                        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                        () -> {
                            idCalls.incrementAndGet();
                            return generatedPot;
                        });
        var prepValid = validEngine.prepare(requestId, inSnapshot()).orElseThrow();
        prepValid.finalizeIn(prepValid::requireCompletionReady);
        assertEquals(1, idCalls.get());
        assertEquals(Outcome.CHANGED, captured[0].outcome());
        assertEquals(1L, captured[0].resultingPotVersion());
        assertEquals(new PotId(generatedPot), captured[0].potId());
        assertEquals(new PotId(generatedPot), protectedPot[0]);
        assertEquals(CreatedResources.pot(generatedPot), captured[0].createdResources());
        assertNull(command.potId());
    }

    @Test
    void createPotCollisionTriggersRetryNotBusinessAlreadyExists() {
        UUID colliding = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID fresh = UUID.fromString("33333333-3333-4333-8333-333333333333");
        var queue = new ArrayDeque<>(List.of(colliding, fresh));
        var command =
                new PotCommand(
                        requestId, eui, "binding-token", null, 0, new PotCommand.CreatePot(new Label("Trip")));
        var captured = new Result[1];

        var store =
                port(
                        PotCommandStore.class,
                        (m, args) ->
                                switch (m.getName()) {
                                    case "load" -> new Stored(command, Optional.empty());
                                    case "protectPot" -> true;
                                    case "finishTerminal" -> {
                                        captured[0] = (Result) args[1];
                                        yield null;
                                    }
                                    case "requireCompletionReady", "publish" -> null;
                                    default -> throw new AssertionError(m);
                                });
        var authority =
                new PotActorAuthority() {
                    public Witness observe(PotCommand c) {
                        return new Witness(1, Optional.of(actor));
                    }

                    public boolean protect(PotCommand c, Witness w) {
                        return true;
                    }
                };
        var history =
                port(
                        PotHistoryPort.class,
                        (m, args) -> {
                            PotId queried = (PotId) args[0];
                            if (queried.value().equals(colliding)) {
                                var facts =
                                        new PotFacts(
                                                queried,
                                                1,
                                                new Versioned<>(1, 1, new PotHeader(queried, false)),
                                                new AuthorizationFacts(queried, 1, actor, actor, false));
                                return Optional.of(
                                        new PotHistoryPort.SelectedPot(
                                                facts,
                                                new ComponentRead.Present<>(
                                                        new Versioned<>(
                                                                1,
                                                                1,
                                                                new PotDetails(
                                                                        queried,
                                                                        new Label("Existing"),
                                                                        actor))),
                                                Map.of(),
                                                Map.of()));
                            }
                            return Optional.empty();
                        });
        var revisions = port(PotRevisionPort.class, (m, args) -> null);
        var engine =
                new ExecutePotCommand(
                        store,
                        (id, ctx) -> RecoveryObservation.COMPLETED,
                        authority,
                        history,
                        revisions,
                        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                        queue::removeFirst);

        // First attempt collides -> throws Collision (technical retry), never ALREADY_EXISTS
        assertThrows(ExecutePotCommand.Collision.class, () -> engine.prepare(requestId, inSnapshot()));
        assertNull(captured[0]);

        // Second attempt allocates fresh UUID and succeeds
        var prepared = engine.prepare(requestId, inSnapshot()).orElseThrow();
        prepared.finalizeIn(prepared::requireCompletionReady);
        assertEquals(Outcome.CHANGED, captured[0].outcome());
        assertEquals(new PotId(fresh), captured[0].potId());
        assertEquals(CreatedResources.pot(fresh), captured[0].createdResources());
    }

    @Test
    void addShareholdersBatchAssignsDistinctIdsWithExactInputIndexCorrespondence() {
        var pot = new PotId(UUID.randomUUID());
        UUID s0 = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        UUID s1 = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        var queue = new ArrayDeque<>(List.of(s0, s1));
        var command =
                new PotCommand(
                        requestId,
                        eui,
                        "binding-token",
                        pot,
                        1,
                        new PotCommand.AddShareholders(
                                List.of(
                                        PotCommand.ShareholderCreation.of(
                                                new Name("SameName"),
                                                PotCommand.Attachment.SELF,
                                                new Weight(Fraction.ONE)),
                                        PotCommand.ShareholderCreation.of(
                                                new Name("SameName"),
                                                PotCommand.Attachment.FREE,
                                                new Weight(new Fraction(2, 1))))));
        var captured = new Result[1];
        @SuppressWarnings("unchecked")
        var createdStates = new List[1];

        var store =
                port(
                        PotCommandStore.class,
                        (m, args) ->
                                switch (m.getName()) {
                                    case "load" -> new Stored(command, Optional.empty());
                                    case "protectPot" -> true;
                                    case "finishTerminal" -> {
                                        captured[0] = (Result) args[1];
                                        yield null;
                                    }
                                    case "requireCompletionReady", "publish" -> null;
                                    default -> throw new AssertionError(m);
                                });
        var authority =
                new PotActorAuthority() {
                    public Witness observe(PotCommand c) {
                        return new Witness(1, Optional.of(actor));
                    }

                    public boolean protect(PotCommand c, Witness w) {
                        return true;
                    }
                };
        var history =
                port(
                        PotHistoryPort.class,
                        (m, args) -> {
                            var facts =
                                    new PotFacts(
                                            pot,
                                            1,
                                            new Versioned<>(1, 1, new PotHeader(pot, false)),
                                            new AuthorizationFacts(pot, 1, actor, actor, false));
                            return Optional.of(
                                    new PotHistoryPort.SelectedPot(
                                            facts,
                                            new ComponentRead.NotLoaded<>(),
                                            Map.of(),
                                            Map.of()));
                        });
        var revisions =
                port(
                        PotRevisionPort.class,
                        (m, args) -> {
                            if (m.getName().equals("createShareholders")) {
                                @SuppressWarnings("unchecked")
                                var changed = (TransitionResult.Changed<List<ShareholderState>>) args[1];
                                createdStates[0] = changed.changes();
                            }
                            return null;
                        });
        var engine =
                new ExecutePotCommand(
                        store,
                        (id, ctx) -> RecoveryObservation.COMPLETED,
                        authority,
                        history,
                        revisions,
                        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                        queue::removeFirst);
        var prepared = engine.prepare(requestId, inSnapshot()).orElseThrow();
        prepared.finalizeIn(prepared::requireCompletionReady);

        assertEquals(Outcome.CHANGED, captured[0].outcome());
        assertEquals(2L, captured[0].resultingPotVersion());
        assertEquals(
                CreatedResources.shareholders(
                        List.of(new CreatedShareholder(0, s0), new CreatedShareholder(1, s1))),
                captured[0].createdResources());
        @SuppressWarnings("unchecked")
        List<ShareholderState> states = createdStates[0];
        assertEquals(Optional.of(actor), states.get(0).details().value().userId());
        assertEquals(Optional.empty(), states.get(1).details().value().userId());
    }

    @Test
    void createExpenseGeneratesExpenseIdAtExecutionAndPublishesCreatedResources() {
        var pot = new PotId(UUID.randomUUID());
        var payer = new ShareholderId(UUID.randomUUID());
        UUID generatedExpense = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
        var command =
                new PotCommand(
                        requestId,
                        eui,
                        "binding-token",
                        pot,
                        2,
                        new PotCommand.CreateExpense(
                                new Label("Dinner"),
                                LocalDate.of(2026, 11, 14),
                                new Amount(new Fraction(10, 3)),
                                payer,
                                List.of(new ExpenseFinancials.Participation(payer, Optional.empty()))));
        var captured = new Result[1];

        var store =
                port(
                        PotCommandStore.class,
                        (m, args) ->
                                switch (m.getName()) {
                                    case "load" -> new Stored(command, Optional.empty());
                                    case "protectPot" -> true;
                                    case "finishTerminal" -> {
                                        captured[0] = (Result) args[1];
                                        yield null;
                                    }
                                    case "requireCompletionReady", "publish" -> null;
                                    default -> throw new AssertionError(m);
                                });
        var authority =
                new PotActorAuthority() {
                    public Witness observe(PotCommand c) {
                        return new Witness(1, Optional.of(actor));
                    }

                    public boolean protect(PotCommand c, Witness w) {
                        return true;
                    }
                };
        var history =
                port(
                        PotHistoryPort.class,
                        (m, args) -> {
                            var facts =
                                    new PotFacts(
                                            pot,
                                            2,
                                            new Versioned<>(1, 1, new PotHeader(pot, false)),
                                            new AuthorizationFacts(pot, 2, actor, actor, true));
                            var shRef =
                                    new ShareholderReference(
                                            pot,
                                            2,
                                            payer,
                                            2,
                                            Optional.of(
                                                    new Versioned<>(
                                                            1, 2, new ShareholderHeader(pot, payer, false))));
                            return Optional.of(
                                    new PotHistoryPort.SelectedPot(
                                            facts,
                                            new ComponentRead.NotLoaded<>(),
                                            Map.of(
                                                    payer,
                                                    new PotHistoryPort.SelectedShareholder(
                                                            shRef,
                                                            new ComponentRead.NotLoaded<>(),
                                                            new ComponentRead.NotLoaded<>())),
                                            Map.of()));
                        });
        var revisions = port(PotRevisionPort.class, (m, args) -> null);
        var engine =
                new ExecutePotCommand(
                        store,
                        (id, ctx) -> RecoveryObservation.COMPLETED,
                        authority,
                        history,
                        revisions,
                        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                        () -> generatedExpense);
        var prepared = engine.prepare(requestId, inSnapshot()).orElseThrow();
        prepared.finalizeIn(prepared::requireCompletionReady);

        assertEquals(Outcome.CHANGED, captured[0].outcome());
        assertEquals(3L, captured[0].resultingPotVersion());
        assertEquals(CreatedResources.expense(generatedExpense), captured[0].createdResources());
    }
}
