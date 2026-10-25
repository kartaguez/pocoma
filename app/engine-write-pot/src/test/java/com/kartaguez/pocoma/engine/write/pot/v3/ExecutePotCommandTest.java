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
    boolean claimValid = true, potValid = true, actorValid = true;
    RuntimeException historyFailure;
    FailureRecorded failureRecorded = FailureRecorded.RETRY_SCHEDULED;
    int failures, preparations;
    PotCommandTransactions transactions =
            new PotCommandTransactions() {
                public <T> T snapshot(Supplier<T> action) {
                    return run(action);
                }

                public <T> T write(Supplier<T> action) {
                    return run(action);
                }

                private <T> T run(Supplier<T> action) {
                    try {
                        return action.get();
                    } catch (RuntimeException failure) {
                        throw new Failure(Resolution.ROLLED_BACK, failure);
                    }
                }
            };

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
                            case "currentVersion" -> 2L;
                            case "protectClaim" -> {
                                calls.add("slot");
                                yield claimValid;
                            }
                            case "protectPot" -> {
                                calls.add("pot");
                                assertEquals(2L, args[1]);
                                yield potValid;
                            }
                            case "finish" -> {
                                calls.add("finish");
                                yield null;
                            }
                            case "publish" -> {
                                calls.add("publish");
                                yield null;
                            }
                            case "recordFailure" -> {
                                failures++;
                                yield failureRecorded;
                            }
                            default -> throw new AssertionError(method);
                        });
    }

    ExecutePotCommand engine() {
        var authority =
                new PotActorAuthority() {
                    public Witness observe(PotCommand c) {
                        return new Witness(1, Optional.of(actor));
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
                transactions,
                store(),
                authority,
                history,
                revisions,
                new RetryPolicy(3, Duration.ZERO),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    @Test
    void u01ChangedUsesOneOrderedFinalUnit() {
        var result = engine().execute(request, claim);
        assertEquals(Status.COMPLETED, result.status());
        assertEquals(3L, result.result().orElseThrow().resultingPotVersion());
        assertEquals(
                List.of(
                        "slot",
                        "actor",
                        "pot",
                        "recordVersion",
                        "revisePotDetails",
                        "finish",
                        "publish"),
                calls);
        assertEquals(1, preparations);
        assertEquals(0, failures);
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
        assertEquals(
                Outcome.UNCHANGED,
                engine().execute(request, claim).result().orElseThrow().outcome());
        assertEquals(List.of("slot", "actor", "pot", "finish", "publish"), calls);
        calls.clear();
        command =
                new PotCommand(
                        request,
                        command.requester(),
                        command.bindingToken(),
                        pot,
                        3,
                        new PotCommand.UpdatePotDetails(new Label("old")));
        assertEquals(
                Outcome.REJECTED,
                engine().execute(request, claim).result().orElseThrow().outcome());
        assertEquals(0, failures);
    }

    @Test
    void u03CollisionNeverRetriesWithinClaimOrPretendsBusinessRejection() {
        potValid = false;
        var attempt = engine().execute(request, claim);
        assertEquals(Status.RETRY_SCHEDULED, attempt.status());
        assertEquals(Cause.COLLISION, attempt.cause());
        assertEquals(1, failures);
        assertEquals(1, preparations);
        assertEquals(List.of("slot", "actor", "pot"), calls);
        assertEquals(2, command.observedVersion());
    }

    @Test
    void u04LostClaimNeverWritesOrConsumesFailureBudget() {
        claimValid = false;
        assertEquals(Status.CLAIM_LOST, engine().execute(request, claim).status());
        assertEquals(List.of("slot"), calls);
        assertEquals(0, failures);
    }

    @Test
    void u05TechnicalAndInconsistentFactsRemainDistinctAndCanExhaustPolicy() {
        historyFailure = new IllegalStateException("unavailable");
        failureRecorded = FailureRecorded.QUARANTINED;
        var technical = engine().execute(request, claim);
        assertEquals(Status.QUARANTINED, technical.status());
        assertEquals(Cause.TECHNICAL, technical.cause());
        historyFailure = new InconsistentPotFactsException("mixed facts");
        assertEquals(Cause.INCONSISTENT_FACTS, engine().execute(request, claim).cause());
        assertTrue(calls.isEmpty());
        assertEquals(2, failures);
    }

    @Test
    void u06TerminalReplaySkipsPreparationAndAllWrites() {
        terminal = Optional.of(new Result(Outcome.CHANGED, 3L, null));
        assertEquals(terminal, engine().execute(request, claim).result());
        assertEquals(0, preparations);
        assertTrue(calls.isEmpty());
        assertFalse(command.toString().contains("secret"));
    }
}
