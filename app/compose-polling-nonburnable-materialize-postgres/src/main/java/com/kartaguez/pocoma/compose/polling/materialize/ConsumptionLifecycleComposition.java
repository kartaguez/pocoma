package com.kartaguez.pocoma.compose.polling.materialize;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier.FailureKind;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoverySettings;
import com.kartaguez.pocoma.contract.consumption.spi.LifecycleSettings;
import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition;
import com.kartaguez.pocoma.infra.postgres.core.bootstrap.BootstrapGuard;
import com.kartaguez.pocoma.infra.postgres.core.consumption.JdbcConsumptionSlotStore;
import com.kartaguez.pocoma.infra.postgres.core.consumption.JdbcDiscoveryAdmissionStore;
import com.kartaguez.pocoma.infra.postgres.core.consumption.JdbcPartitionOwnershipStore;
import com.kartaguez.pocoma.infra.postgres.core.consumption.JdbcSlotKeyNavigation;
import com.kartaguez.pocoma.infra.postgres.source.JdbcEventDiscoveryReferences;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionEngine;
import com.kartaguez.pocoma.orchestrator.consumption.WorkerCapacity;
import com.kartaguez.pocoma.port.transaction.AttemptTransactions;

/** Explicit construction only. Pool sizing is a prerequisite, NOT a connection reservation. */
public final class ConsumptionLifecycleComposition {
    private ConsumptionLifecycleComposition() {}

    public static ConsumptionEngine assemble(
            BootstrapGuard guard,
            ConsumptionDefinition definition,
            DataSource source,
            AttemptTransactions transactions,
            ConsumptionParticipant participant,
            LifecycleSettings settings,
            WorkerCapacity capacity,
            int maximumConnections,
            AttemptFailureClassifier classifier) {
        return assemble(
                guard,
                definition,
                source,
                transactions,
                participant,
                settings,
                capacity,
                maximumConnections,
                classifier,
                BootstrapGuard.Privileges.LIFECYCLE_ONLY);
    }

    public static ConsumptionEngine assemble(
            BootstrapGuard guard,
            ConsumptionDefinition definition,
            DataSource source,
            AttemptTransactions transactions,
            ConsumptionParticipant participant,
            LifecycleSettings settings,
            WorkerCapacity capacity,
            int maximumConnections,
            AttemptFailureClassifier classifier,
            BootstrapGuard.Privileges privileges) {
        Objects.requireNonNull(guard).validateLifecyclePrivileges(privileges);
        guard.validateLifecycleSource(source, definition.authority(), privileges);
        if (privileges == BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY) {
            requireSharedOwner(transactions, source);
        }
        verifyPoolCapacity(source, maximumConnections, capacity.workerLimit());
        return new ConsumptionEngine(
                definition,
                transactions,
                new JdbcConsumptionSlotStore(source),
                participant,
                new JdbcPartitionOwnershipStore(source),
                settings,
                capacity,
                classifier);
    }

    private static void requireSharedOwner(AttemptTransactions transactions, DataSource source) {
        if (!(transactions
                instanceof com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions owner)) {
            throw new IllegalArgumentException("explicit shared physical owner required");
        }
        owner.requireSource(source);
    }

    private static void verifyPoolCapacity(
            DataSource source, int maximumConnections, int workerLimit) {
        if (source instanceof com.zaxxer.hikari.HikariDataSource pool
                && maximumConnections != pool.getMaximumPoolSize()) {
            throw new IllegalArgumentException(
                    "declared capacity differs from the actual Hikari pool");
        }
        if (maximumConnections < workerLimit + 4) {
            throw new IllegalArgumentException(
                    "M_W + control + two claim renewals + lease access required");
        }
    }

    private static void verifyBusinessControlReserves(
            com.zaxxer.hikari.HikariDataSource business,
            com.zaxxer.hikari.HikariDataSource control,
            int workerLimit) {
        if (business == control) {
            throw new IllegalArgumentException("distinct Business/Control reserves required");
        }
        if (business.getMaximumPoolSize() < workerLimit + 2
                || control.getMaximumPoolSize() < workerLimit + 6) {
            throw new IllegalArgumentException("distinct Business/Control reserves required");
        }
    }

    /** Four-pool runtime: effects and control units are consecutive, never nested. */
    public static ConsumptionEngine assemble(
            BootstrapGuard guard,
            ConsumptionDefinition definition,
            com.zaxxer.hikari.HikariDataSource business,
            com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions effects,
            ConsumptionParticipant participant,
            com.zaxxer.hikari.HikariDataSource control,
            com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions technical,
            LifecycleSettings settings,
            WorkerCapacity capacity,
            AttemptFailureClassifier classifier,
            com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation observation) {
        verifyBusinessControlReserves(business, control, capacity.workerLimit());
        effects.requireSource(business);
        technical.requireSource(control);
        var profile = BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY;
        guard.validateLifecycleSource(business, definition.authority(), profile);
        guard.validateLifecycleSource(control, definition.authority(), profile);
        return new ConsumptionEngine(
                definition,
                effects,
                new JdbcConsumptionSlotStore(business),
                participant,
                technical,
                new JdbcConsumptionSlotStore(control),
                new JdbcPartitionOwnershipStore(control),
                settings,
                capacity,
                classifier,
                new JdbcSlotKeyNavigation(control, settings),
                observation);
    }

    /** READ explicitly owns its PRIMARY source and its effects snapshot independently. */
    public static ConsumptionEngine assemble(
            BootstrapGuard guard,
            ConsumptionDefinition definition,
            com.zaxxer.hikari.HikariDataSource business,
            com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions effects,
            ConsumptionParticipant participant,
            com.zaxxer.hikari.HikariDataSource control,
            com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions technical,
            DataSource writeSource,
            com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions sourceReads,
            DiscoverySettings discoverySettings,
            LifecycleSettings settings,
            WorkerCapacity capacity,
            AttemptFailureClassifier classifier,
            com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation observation) {
        if (definition.authority() != ConsumptionDefinition.Authority.READ)
            throw new IllegalArgumentException("Explicit event recovery is READ only");
        verifyBusinessControlReserves(business, control, capacity.workerLimit());
        effects.requireSource(business);
        technical.requireSource(control);
        sourceReads.requireSource(writeSource);
        var profile = BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY;
        guard.validateLifecycleSource(business, definition.authority(), profile);
        guard.validateLifecycleSource(control, definition.authority(), profile);
        guard.validateLifecycleSource(writeSource, ConsumptionDefinition.Authority.WRITE, profile);
        return new ConsumptionEngine(
                definition,
                effects,
                new JdbcConsumptionSlotStore(business),
                participant,
                sourceReads,
                Optional.of(
                        new JdbcEventDiscoveryReferences(
                                writeSource, definition.family(), discoverySettings)),
                r -> ConsumptionDiscoveryComposition.relevant(definition, r),
                new JdbcDiscoveryAdmissionStore(business, discoverySettings),
                technical,
                new JdbcConsumptionSlotStore(control),
                new JdbcPartitionOwnershipStore(control),
                settings,
                capacity,
                classifier,
                new JdbcSlotKeyNavigation(control, settings),
                observation);
    }

    public static FailureKind classify(Throwable failure) {return com.kartaguez.pocoma.infra.tx.spring.ConsumptionFailureClassifier.classify(failure);}
}
