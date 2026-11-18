package com.kartaguez.pocoma.compose.polling.process;
import com.kartaguez.pocoma.infra.observability.micrometer.StageHandoffTracker;
import com.kartaguez.pocoma.contract.consumption.spi.LifecycleSettings;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionEngine;
import com.kartaguez.pocoma.orchestrator.consumption.WorkerCapacity;

import com.kartaguez.pocoma.domain.consumption.ConsumableFamily;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier.FailureKind;
import com.kartaguez.pocoma.engine.write.pot.port.execution.PotActorAuthority;
import com.kartaguez.pocoma.engine.write.pot.ExecutePotCommand;
import com.kartaguez.pocoma.infra.postgres.write.admission.*;
import com.kartaguez.pocoma.infra.postgres.write.pot.*;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.infra.postgres.core.bootstrap.BootstrapGuard;

import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

import javax.sql.DataSource;

/** Explicit factory only. I1 remains inert; no Engine bean, worker or polling is installed. */
public final class PotCommandComposition {
    private PotCommandComposition() {}

    public static ConsumptionEngine assemble(
            DataSource writeSource,
            PlatformTransactionManager manager,
            PotActorAuthority identityAuthority,
            Clock clock,
            BootstrapGuard guard,
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
                BootstrapGuard.Privileges.LIFECYCLE_ONLY);
    }

    /** Joint I4 assembly: the caller injects the very same physical owner used by Discovery. */
    public static ConsumptionEngine assemble(
            DataSource writeSource,
            SpringAttemptTransactions transactions,
            PotActorAuthority identityAuthority,
            Clock clock,
            BootstrapGuard guard,
            LifecycleSettings settings,
            WorkerCapacity capacity,
            int maximumConnections,
            BootstrapGuard.Privileges privileges) {
        transactions.requireSource(writeSource);
        var participant =
                new ExecutePotCommand(
                        new JdbcPotCommandStore(
                                writeSource,
                                transactions,
                                (cmd, pot, ver, potEvent, completionEvent) -> {}),
                        new JdbcPotCommandObservation(writeSource),
                        identityAuthority,
                        new JdbcPotHistoryAdapter(writeSource),
                        new JdbcPotRevisionAdapter(writeSource),
                        clock);
        var definition =
                com.kartaguez.pocoma.domain.consumption.ConsumptionCatalog.definitions().stream()
                        .filter(d -> d.family() == ConsumableFamily.COMMAND)
                        .findFirst()
                        .orElseThrow();
        return ConsumptionLifecycleComposition.assemble(
                guard,
                definition,
                writeSource,
                transactions,
                participant,
                settings,
                capacity,
                maximumConnections,
                PotCommandComposition::classify,
                privileges);
    }

    private static FailureKind classify(Throwable cause) {
        var common = ConsumptionLifecycleComposition.classify(cause);
        if (common == FailureKind.NON_COUNTABLE) return common;
        var seen =
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<Throwable, Boolean>());
        var queue = new java.util.ArrayDeque<Throwable>();
        if (cause != null) queue.add(cause);
        while (!queue.isEmpty()) {
            var next = queue.removeFirst();
            if (!seen.add(next)) continue;
            var specific = classifyCauseNode(next);
            if (specific != null) return specific;
            if (next.getCause() != null) queue.add(next.getCause());
            java.util.Collections.addAll(queue, next.getSuppressed());
        }
        return common;
    }

    private static FailureKind classifyCauseNode(Throwable next) {
        if (next instanceof ExecutePotCommand.Collision) return FailureKind.COLLISION;
        if (next instanceof com.kartaguez.pocoma.domain.pot.revision.InconsistentPotFactsException) {
            return FailureKind.INCONSISTENT_FACTS;
        }
        if (!(next instanceof org.postgresql.util.PSQLException sql)) return null;
        if (!"40001".equals(sql.getSQLState()) || sql.getServerErrorMessage() == null) return null;
        return "v3_invitation_final_expiry".equals(sql.getServerErrorMessage().getConstraint())
                ? FailureKind.COLLISION
                : null;
    }

    public static ConsumptionEngine assemble(
            com.zaxxer.hikari.HikariDataSource business,
            SpringAttemptTransactions effects,
            com.zaxxer.hikari.HikariDataSource control,
            SpringAttemptTransactions technical,
            PotActorAuthority identity,
            Clock clock,
            BootstrapGuard guard,
            LifecycleSettings settings,
            WorkerCapacity capacity,
            com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation observation) {
        return assemble(
                business,
                effects,
                control,
                technical,
                identity,
                clock,
                guard,
                settings,
                capacity,
                observation,
                null);
    }

    public static ConsumptionEngine assemble(
            com.zaxxer.hikari.HikariDataSource business,
            SpringAttemptTransactions effects,
            com.zaxxer.hikari.HikariDataSource control,
            SpringAttemptTransactions technical,
            PotActorAuthority identity,
            Clock clock,
            BootstrapGuard guard,
            LifecycleSettings settings,
            WorkerCapacity capacity,
            com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation observation,
            com.kartaguez.pocoma.engine.write.pot.port.execution.InvitationStore invitations) {
        return assemble(business,effects,control,technical,identity,clock,guard,settings,capacity,observation,invitations,(cmd,pot,ver,potEvent,completionEvent) -> {});
    }

    public static ConsumptionEngine assemble(
            com.zaxxer.hikari.HikariDataSource business,
            SpringAttemptTransactions effects,
            com.zaxxer.hikari.HikariDataSource control,
            SpringAttemptTransactions technical,
            PotActorAuthority identity,
            Clock clock,
            BootstrapGuard guard,
            LifecycleSettings settings,
            WorkerCapacity capacity,
            com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation observation,
            com.kartaguez.pocoma.engine.write.pot.port.execution.InvitationStore invitations, JdbcPotCommandStore.PublicationListener publicationListener) {
        effects.requireSource(business);
        var participant =
                new ExecutePotCommand(
                        new JdbcPotCommandStore(
                                business,
                                effects,
                                publicationListener),
                        new JdbcPotCommandObservation(business),
                        identity,
                        new JdbcPotHistoryAdapter(business),
                        new JdbcPotRevisionAdapter(business),
                        clock);
        if (invitations != null)
            participant =
                    new ExecutePotCommand(
                            new JdbcPotCommandStore(
                                    business,
                                    effects,
                                    publicationListener),
                            new JdbcPotCommandObservation(business),
                            identity,
                            new JdbcPotHistoryAdapter(business),
                            new JdbcPotRevisionAdapter(business),
                            clock,
                            invitations);
        var definition = com.kartaguez.pocoma.domain.consumption.ConsumptionCatalog.definitions().getFirst();
        return ConsumptionLifecycleComposition.assemble(
                guard,
                definition,
                business,
                effects,
                participant,
                control,
                technical,
                settings,
                capacity,
                PotCommandComposition::classify,
                observation);
    }
}
