package com.kartaguez.pocoma.runtime.consumption.v3;
import com.kartaguez.pocoma.infra.postgres.core.v3.V3BootstrapGuard;
import com.kartaguez.pocoma.infra.postgres.source.v3.*;
import com.kartaguez.pocoma.infra.postgres.core.v3.*;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoverySettings;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.spi.LifecycleSettings;
import com.kartaguez.pocoma.orchestrator.consumption.v3.ConsumptionEngine;
import com.kartaguez.pocoma.orchestrator.consumption.v3.DiscoveryEngine;
import com.kartaguez.pocoma.orchestrator.consumption.v3.WorkerCapacity;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier.FailureKind;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.*;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import javax.sql.DataSource;

/** Explicit construction only. Pool sizing is a prerequisite, NOT a connection reservation. */
public final class V3ConsumptionLifecycleComposition {
    private V3ConsumptionLifecycleComposition() {}

    public static ConsumptionEngine assemble(
            V3BootstrapGuard guard,
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
                V3BootstrapGuard.Privileges.LIFECYCLE_ONLY);
    }

    public static ConsumptionEngine assemble(
            V3BootstrapGuard guard,
            ConsumptionDefinition definition,
            DataSource source,
            AttemptTransactions transactions,
            ConsumptionParticipant participant,
            LifecycleSettings settings,
            WorkerCapacity capacity,
            int maximumConnections,
            AttemptFailureClassifier classifier,
            V3BootstrapGuard.Privileges privileges) {
        Objects.requireNonNull(guard).validateLifecyclePrivileges(privileges);
        guard.validateLifecycleSource(source, definition.authority(), privileges);
        if (privileges == V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY) {
            if (!(transactions
                    instanceof
                    com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions owner))
                throw new IllegalArgumentException("explicit shared physical owner required");
            owner.requireSource(source);
        }
        if (source instanceof com.zaxxer.hikari.HikariDataSource pool
                && maximumConnections != pool.getMaximumPoolSize())
            throw new IllegalArgumentException(
                    "declared capacity differs from the actual Hikari pool");
        if (maximumConnections < capacity.workerLimit() + 4)
            throw new IllegalArgumentException(
                    "M_W + control + two claim renewals + lease access required");
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

    /** Four-pool runtime: effects and control units are consecutive, never nested. */
    public static ConsumptionEngine assemble(
            V3BootstrapGuard guard,
            ConsumptionDefinition definition,
            com.zaxxer.hikari.HikariDataSource business,
            com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions effects,
            ConsumptionParticipant participant,
            com.zaxxer.hikari.HikariDataSource control,
            com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions technical,
            LifecycleSettings settings,
            WorkerCapacity capacity,
            AttemptFailureClassifier classifier,
            com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation observation) {
        if (business == control
                || business.getMaximumPoolSize() < capacity.workerLimit() + 2
                || control.getMaximumPoolSize() < capacity.workerLimit() + 6)
            throw new IllegalArgumentException("distinct Business/Control reserves required");
        effects.requireSource(business);
        technical.requireSource(control);
        var profile = V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY;
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
            V3BootstrapGuard guard,
            ConsumptionDefinition definition,
            com.zaxxer.hikari.HikariDataSource business,
            com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions effects,
            ConsumptionParticipant participant,
            com.zaxxer.hikari.HikariDataSource control,
            com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions technical,
            DataSource writeSource,
            com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions sourceReads,
            DiscoverySettings discoverySettings,
            LifecycleSettings settings,
            WorkerCapacity capacity,
            AttemptFailureClassifier classifier,
            com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation observation) {
        if (definition.authority() != ConsumptionDefinition.Authority.READ)
            throw new IllegalArgumentException("Explicit event recovery is READ only");
        if (business == control
                || business.getMaximumPoolSize() < capacity.workerLimit() + 2
                || control.getMaximumPoolSize() < capacity.workerLimit() + 6)
            throw new IllegalArgumentException("distinct Business/Control reserves required");
        effects.requireSource(business);
        technical.requireSource(control);
        sourceReads.requireSource(writeSource);
        var profile = V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY;
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
                r -> V3ConsumptionDiscoveryComposition.relevant(definition, r),
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

    public static FailureKind classify(Throwable failure) {return com.kartaguez.pocoma.infra.tx.spring.v3.ConsumptionFailureClassifier.classify(failure);}
}
