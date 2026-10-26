package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.pot.v3.InconsistentPotFactsException;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionSlotStore;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommandStore.Result;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.v3.*;
import com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.JdbcConsumptionSlotStore;
import com.kartaguez.pocoma.port.transaction.v3.*;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

/**
 * Historical cession assertions are fixture proofs, never certification of the future I3 Engine.
 */
final class L1CommandCompatibilityHarness {
    enum Status {
        COMPLETED,
        RETRY_SCHEDULED,
        QUARANTINED,
        CLAIM_LOST,
        RECOVERY_PENDING
    }

    enum Cause {
        NONE,
        COLLISION,
        TECHNICAL,
        INCONSISTENT_FACTS,
        COMMIT_UNCERTAIN
    }

    record Attempt(Status status, Optional<Result> result, Cause cause) {}

    record RetryPolicy(int maximumFailures, Duration delay) {}

    final ThreadLocal<Result> terminal = new ThreadLocal<>();
    final ConsumptionEngine engine;
    final L1CommandStore store;
    final RetryPolicy policy;
    final AttemptTransactions transactions;

    L1CommandCompatibilityHarness(
            AttemptTransactions transactions,
            L1CommandStore store,
            PotActorAuthority authority,
            PotHistoryPort history,
            PotRevisionPort revisions,
            RetryPolicy policy,
            Clock clock) {
        this.transactions = transactions;
        this.store = store;
        this.policy = policy;
        var canonical = new JdbcConsumptionSlotStore(store.source());
        var slots =
                new ConsumptionSlotStore() {
                    public Protection protectFinal(ClaimedSlot claim) {
                        return store.protectClaim(claim.consumableId(), claim.claimToken())
                                ? Protection.VALID
                                : Protection.STALE_OR_EXPIRED;
                    }

                    public void deleteProtected(ClaimedSlot claim) {
                        canonical.deleteProtected(claim);
                    }

                    public SlotObservation observe(
                            ConsumptionId c, UUID id, RecoveryReadContext context) {
                        return canonical.observe(c, id, context);
                    }
                };
        var recording =
                (PotCommandStore)
                        java.lang.reflect.Proxy.newProxyInstance(
                                PotCommandStore.class.getClassLoader(),
                                new Class<?>[] {PotCommandStore.class},
                                (proxy, method, args) -> {
                                    try {
                                        var value = method.invoke(store, args);
                                        if (method.getName().equals("load"))
                                            ((PotCommandStore.Stored) value)
                                                    .terminal()
                                                    .ifPresent(terminal::set);
                                        if (method.getName().equals("finishTerminal"))
                                            terminal.set((Result) args[1]);
                                        return value;
                                    } catch (java.lang.reflect.InvocationTargetException failure) {
                                        throw failure.getCause();
                                    }
                                });
        engine =
                new ConsumptionEngine(
                        new ConsumptionDefinition(
                                L1CommandStore.claim(new UUID(0, 1), new UUID(0, 2))
                                        .consumptionId(),
                                ConsumableFamily.COMMAND,
                                ConsumptionDefinition.Authority.WRITE),
                        transactions,
                        slots,
                        new ExecutePotCommand(
                                recording,
                                new JdbcPotCommandObservation(store.source()),
                                authority,
                                history,
                                revisions,
                                clock));
    }

    Attempt execute(UUID request, UUID token) {
        terminal.remove();
        var attempt = engine.execute(L1CommandStore.claim(request, token));
        return switch (attempt.status()) {
            case COMMITTED ->
                    new Attempt(Status.COMPLETED, Optional.ofNullable(terminal.get()), Cause.NONE);
            case CLAIM_LOST -> new Attempt(Status.CLAIM_LOST, Optional.empty(), Cause.NONE);
            case RECOVERY_PENDING, INCONSISTENT ->
                    new Attempt(Status.RECOVERY_PENDING, Optional.empty(), Cause.COMMIT_UNCERTAIN);
            case ROLLED_BACK -> {
                var fault = attempt.cause();
                var cause =
                        fault instanceof ExecutePotCommand.Collision
                                ? Cause.COLLISION
                                : fault instanceof InconsistentPotFactsException
                                        ? Cause.INCONSISTENT_FACTS
                                        : Cause.TECHNICAL;
                // Deliberately outside the Engine owner and effects transaction. SQL fixture only.
                var manager = new DataSourceTransactionManager(store.source());
                manager.setNestedTransactionAllowed(false);
                manager.setRollbackOnCommitFailure(true);
                try {
                    var recorded =
                            new TransactionTemplate(manager)
                                    .execute(
                                            status ->
                                                    store.recordFailure(
                                                            request,
                                                            token,
                                                            policy.maximumFailures(),
                                                            policy.delay()));
                    yield new Attempt(Status.valueOf(recorded.name()), Optional.empty(), cause);
                } catch (RuntimeException failure) {
                    yield new Attempt(
                            Status.RECOVERY_PENDING, Optional.empty(), Cause.COMMIT_UNCERTAIN);
                }
            }
        };
    }
}
