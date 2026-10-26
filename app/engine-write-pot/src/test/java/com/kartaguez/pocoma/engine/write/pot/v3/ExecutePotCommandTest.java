package com.kartaguez.pocoma.engine.write.pot.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommandStore.*;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.v3.*;
import com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand.*;

import org.junit.jupiter.api.Test;

import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.function.*;

class ExecutePotCommandTest {
    final UUID request = UUID.randomUUID(), claim = UUID.randomUUID();
    final PotId pot = new PotId(UUID.randomUUID());
    final UserId actor = new UserId(UUID.randomUUID());
    final List<String> calls = new ArrayList<>();
    PotCommand command =
            new PotCommand(
                    request,
                    new PotCommand.Eui("issuer", "subject"),
                    "secret",
                    pot,
                    2,
                    new PotCommand.UpdatePotDetails(new Label("new")));
    Optional<Result> terminal = Optional.empty();
    boolean claimValid = true, potValid = true, actorValid = true, bindingValid = true;
    RuntimeException historyFailure;
    int preparations;
    Result finished;

    Result execute() {
        var prepared = engine().prepare(request).orElseThrow();
        prepared.finalizeIn(
                () -> {
                    calls.add("complete");
                    prepared.requireCompletionReady();
                });
        return finished;
    }

    @SuppressWarnings("unchecked")
    <T> T port(Class<T> type, BiFunction<Method, Object[], Object> invoke) {
        return (T)
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (proxy, method, args) -> invoke.apply(method, args));
    }

    PotCommandStore store() {
        return port(
                PotCommandStore.class,
                (method, args) ->
                        switch (method.getName()) {
                            case "load" -> new Stored(command, terminal);
                            case "currentVersion" -> {
                                throw new AssertionError("Unexpected Pot version read");
                            }
                            case "protectPot" -> {
                                calls.add("pot");
                                assertEquals(2L, args[1]);
                                yield potValid;
                            }
                            case "finishTerminal" -> {
                                finished = (Result) args[1];
                                calls.add("finish");
                                yield null;
                            }
                            case "publish" -> {
                                calls.add("publish");
                                yield null;
                            }
                            case "requireCompletionReady" -> {
                                calls.add("ready");
                                yield null;
                            }
                            default -> throw new AssertionError(method);
                        });
    }

    ExecutePotCommand engine() {
        var authority =
                new PotActorAuthority() {
                    public Witness observe(PotCommand c) {
                        return new Witness(1, bindingValid ? Optional.of(actor) : Optional.empty());
                    }

                    public boolean protect(PotCommand c, Witness witness) {
                        calls.add("actor");
                        return actorValid;
                    }
                };
        var history =
                port(
                        PotHistoryPort.class,
                        (method, args) -> {
                            preparations++;
                            if (historyFailure != null) throw historyFailure;
                            var facts =
                                    new PotFacts(
                                            pot,
                                            2,
                                            new Versioned<>(1, 1, new PotHeader(pot, false)),
                                            new AuthorizationFacts(pot, 2, actor, actor, false));
                            return Optional.of(
                                    new PotHistoryPort.SelectedPot(
                                            facts,
                                            new ComponentRead.Present<>(
                                                    new Versioned<>(
                                                            1,
                                                            1,
                                                            new PotDetails(
                                                                    pot, new Label("old"), actor))),
                                            Map.of(),
                                            Map.of()));
                        });
        var revisions =
                port(
                        PotRevisionPort.class,
                        (method, args) -> {
                            calls.add(method.getName());
                            return null;
                        });
        return new ExecutePotCommand(
                store(),
                (id, context) ->
                        com.kartaguez.pocoma.engine.consumption.v3.RecoveryObservation.COMPLETED,
                authority,
                history,
                revisions,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    @Test
    void u01ChangedUsesOneOrderedFinalUnit() {
        assertEquals(3L, execute().resultingPotVersion());
        assertEquals(
                List.of(
                        "actor",
                        "pot",
                        "recordVersion",
                        "revisePotDetails",
                        "finish",
                        "complete",
                        "ready",
                        "publish"),
                calls);
        assertEquals(1, preparations);
    }

    @Test
    void u02NoopAndBusinessRejectionNeverWriteRevisions() {
        command =
                new PotCommand(
                        request,
                        command.requester(),
                        command.bindingToken(),
                        pot,
                        2,
                        new PotCommand.UpdatePotDetails(new Label("old")));
        assertEquals(Outcome.UNCHANGED, execute().outcome());
        assertEquals(List.of("actor", "pot", "finish", "complete", "ready", "publish"), calls);
        calls.clear();
        command =
                new PotCommand(
                        request,
                        command.requester(),
                        command.bindingToken(),
                        pot,
                        3,
                        new PotCommand.UpdatePotDetails(new Label("old")));
        assertEquals(Outcome.REJECTED, execute().outcome());
        assertEquals(List.of("actor", "pot", "finish", "complete", "ready", "publish"), calls);
    }

    @Test
    void u03CollisionNeverRetriesWithinClaimOrPretendsBusinessRejection() {
        potValid = false;
        assertThrows(Collision.class, this::execute);
        assertNull(finished);
        assertEquals(1, preparations);
        assertEquals(List.of("actor", "pot"), calls);
        assertEquals(2, command.observedVersion());
    }

    @Test
    void u04LostClaimIsEngineResponsibilityAndCannotReachThisParticipant() {
        // Port shape: Command has neither a transaction owner nor a claim/slot dependency.
        assertTrue(
                Arrays.stream(ExecutePotCommand.class.getDeclaredFields())
                        .noneMatch(
                                f ->
                                        f.getType().getName().contains("Transactions")
                                                || f.getType().getName().contains("Slot")));
    }

    @Test
    void u05TechnicalAndInconsistentFactsRemainDistinct() {
        historyFailure = new IllegalStateException("unavailable");
        assertSame(historyFailure, assertThrows(IllegalStateException.class, this::execute));
        historyFailure = new InconsistentPotFactsException("mixed facts");
        assertSame(
                historyFailure, assertThrows(InconsistentPotFactsException.class, this::execute));
        assertTrue(calls.isEmpty());
    }

    @Test
    void u06TerminalReplaySkipsPreparationAndAllWrites() {
        terminal = Optional.of(new Result(Outcome.CHANGED, 3L, null));
        assertTrue(engine().prepare(request).isEmpty());
        assertEquals(0, preparations);
        assertTrue(calls.isEmpty());
        assertFalse(command.toString().contains("secret"));
    }

    @Test
    void f02InvalidBindingNeverReadsOrProtectsPotAndStillFencesIdentity() {
        bindingValid = false;
        historyFailure = new IllegalStateException("Pot must not be read");
        assertEquals("INVALID_BINDING", execute().rejection());
        assertEquals(List.of("actor", "finish", "complete", "ready", "publish"), calls);
        assertEquals(0, preparations);
        calls.clear();
        actorValid = false;
        assertThrows(Collision.class, this::execute);
        assertEquals(List.of("actor"), calls);
    }
}
