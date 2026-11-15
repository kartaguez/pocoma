package com.kartaguez.pocoma.compose.polling.materialize;
import com.kartaguez.pocoma.infra.postgres.core.BootstrapGuard;
import com.kartaguez.pocoma.infra.observability.micrometer.StageHandoffTracker;
import com.kartaguez.pocoma.infra.postgres.source.*;
import com.kartaguez.pocoma.infra.postgres.core.*;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoverySettings;

import com.kartaguez.pocoma.domain.consumption.*;
import com.kartaguez.pocoma.orchestrator.consumption.DiscoveryEngine;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;

import java.util.Objects;

import javax.sql.DataSource;

/** Explicit inactive factories. No manager, connection pool, thread, bean or grant is created. */
public final class ConsumptionDiscoveryComposition {
    private ConsumptionDiscoveryComposition() {}

    private static void effects(
            BootstrapGuard guard,
            ConsumptionDefinition definition,
            DataSource source,
            SpringAttemptTransactions owner,
            BootstrapGuard.Privileges privileges) {
        if (Objects.requireNonNull(privileges)
                != BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY)
            throw new IllegalArgumentException(
                    "Discovery requires an explicitly selected combined profile");
        if (!com.kartaguez.pocoma.domain.consumption.ConsumptionCatalog.definitions().contains(definition)
                && !MaterializeSettings.probeDefinitions().contains(definition))
            throw new IllegalArgumentException("unknown pilot definition");
        Objects.requireNonNull(guard).validateLifecyclePrivileges(privileges);
        guard.validateLifecycleSource(source, definition.authority(), privileges);
        Objects.requireNonNull(owner).requireSource(source);
    }

    public static boolean relevant(
            ConsumptionDefinition definition,
            com.kartaguez.pocoma.contract.consumption.spi.NonBurnableReferences.Reference
                    reference) {
        return definition.family() != ConsumableFamily.IDENTITY_EVENT
                || reference.subjectType().equals("EXTERNAL_USER_IDENTITY");
    }

    public static DiscoveryEngine events(
            BootstrapGuard guard,
            ConsumptionDefinition definition,
            DataSource effectsSource,
            SpringAttemptTransactions effectsOwner,
            DataSource writeSource,
            SpringAttemptTransactions sourceReads,
            DiscoverySettings settings,
            BootstrapGuard.Privileges privileges) {
        if (definition.family() != ConsumableFamily.INVITATION_EVENT
                && definition.family() != ConsumableFamily.POT_EVENT
                && definition.family() != ConsumableFamily.COMMAND_COMPLETION_EVENT
                && definition.family() != ConsumableFamily.IDENTITY_EVENT
                && definition.family() != ConsumableFamily.IDENTITY_COMPLETION_EVENT)
            throw new IllegalArgumentException(
                    "production publication source not delivered: " + definition.family());
        effects(guard, definition, effectsSource, effectsOwner, privileges);
        guard.validateLifecycleSource(
                writeSource, ConsumptionDefinition.Authority.WRITE, privileges);
        sourceReads.requireSource(writeSource);
        return new DiscoveryEngine(
                definition,
                effectsOwner,
                new JdbcPartitionOwnershipStore(effectsSource),
                new JdbcDiscoveryAdmissionStore(effectsSource, settings),
                settings,
                sourceReads,
                new JdbcEventDiscoveryReferences(writeSource, definition.family(), settings),
                r -> relevant(definition, r));
    }
}
