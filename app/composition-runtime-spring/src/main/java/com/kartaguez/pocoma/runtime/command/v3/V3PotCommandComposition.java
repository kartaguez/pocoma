package com.kartaguez.pocoma.runtime.command.v3;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumableFamily;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.AttemptFailureClassifier.FailureKind;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotActorAuthority;
import com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.runtime.consumption.v3.V3BootstrapGuard;
import com.kartaguez.pocoma.runtime.consumption.v3.V3ConsumptionCatalogConfiguration;
import com.kartaguez.pocoma.runtime.consumption.v3.V3ConsumptionLifecycleComposition;

import org.springframework.orm.jpa.JpaTransactionManager;

import java.time.Clock;

import javax.sql.DataSource;

/** Explicit factory only. I1 remains inert; no Engine bean, worker or polling is installed. */
public final class V3PotCommandComposition {
    private V3PotCommandComposition() {}

    public static ConsumptionEngine assemble(
            DataSource writeSource,
            JpaTransactionManager manager,
            PotActorAuthority identityAuthority,
            Clock clock,
            V3BootstrapGuard guard,
            LifecycleSettings settings,
            WorkerCapacity capacity,
            int maximumConnections) {
        var transactions = new SpringAttemptTransactions(writeSource, manager, 60);
        return assemble(
                writeSource,
                transactions,
                identityAuthority,
                clock,
                guard,
                settings,
                capacity,
                maximumConnections,
                V3BootstrapGuard.Privileges.LIFECYCLE_ONLY);
    }

    /** Joint I4 assembly: the caller injects the very same physical owner used by Discovery. */
    public static ConsumptionEngine assemble(
            DataSource writeSource,
            SpringAttemptTransactions transactions,
            PotActorAuthority identityAuthority,
            Clock clock,
            V3BootstrapGuard guard,
            LifecycleSettings settings,
            WorkerCapacity capacity,
            int maximumConnections,
            V3BootstrapGuard.Privileges privileges) {
        transactions.requireSource(writeSource);
        var participant =
                new ExecutePotCommand(
                        new JdbcPotCommandStore(writeSource, transactions),
                        new JdbcPotCommandObservation(writeSource),
                        identityAuthority,
                        new JdbcPotHistoryAdapter(writeSource),
                        new JdbcPotRevisionAdapter(writeSource),
                        clock);
        var definition =
                V3ConsumptionCatalogConfiguration.definitions().stream()
                        .filter(d -> d.family() == ConsumableFamily.COMMAND)
                        .findFirst()
                        .orElseThrow();
        return V3ConsumptionLifecycleComposition.assemble(
                guard,
                definition,
                writeSource,
                transactions,
                participant,
                settings,
                capacity,
                maximumConnections,
                V3PotCommandComposition::classify,
                privileges);
    }

    private static FailureKind classify(Throwable cause) {
        var common = V3ConsumptionLifecycleComposition.classify(cause);
        if (common == FailureKind.NON_COUNTABLE) return common;
        var seen =
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<Throwable, Boolean>());
        var queue = new java.util.ArrayDeque<Throwable>();
        if (cause != null) queue.add(cause);
        while (!queue.isEmpty()) {
            var next = queue.removeFirst();
            if (!seen.add(next)) continue;
            if (next instanceof ExecutePotCommand.Collision) return FailureKind.COLLISION;
            if (next instanceof com.kartaguez.pocoma.domain.pot.v3.InconsistentPotFactsException)
                return FailureKind.INCONSISTENT_FACTS;
            if (next.getCause() != null) queue.add(next.getCause());
            java.util.Collections.addAll(queue, next.getSuppressed());
        }
        return common;
    }

    public static ConsumptionEngine assemble(
            com.zaxxer.hikari.HikariDataSource business,
            SpringAttemptTransactions effects,
            com.zaxxer.hikari.HikariDataSource control,
            SpringAttemptTransactions technical,
            PotActorAuthority identity,
            Clock clock,
            V3BootstrapGuard guard,
            LifecycleSettings settings,
            WorkerCapacity capacity,
            com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionObservation observation) {
        effects.requireSource(business);
        var participant =
                new ExecutePotCommand(
                        new JdbcPotCommandStore(business, effects),
                        new JdbcPotCommandObservation(business),
                        identity,
                        new JdbcPotHistoryAdapter(business),
                        new JdbcPotRevisionAdapter(business),
                        clock);
        var definition = V3ConsumptionCatalogConfiguration.definitions().getFirst();
        return V3ConsumptionLifecycleComposition.assemble(
                guard,
                definition,
                business,
                effects,
                participant,
                control,
                technical,
                settings,
                capacity,
                V3PotCommandComposition::classify,
                observation);
    }
}
