package com.kartaguez.pocoma.compose.polling.materialize.v3;
import com.kartaguez.pocoma.infra.postgres.core.v3.V3BootstrapGuard;
import com.kartaguez.pocoma.infra.observability.micrometer.v3.StageHandoffTracker;
import com.kartaguez.pocoma.infra.postgres.source.v3.*;
import com.kartaguez.pocoma.infra.postgres.core.v3.*;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoverySettings;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.orchestrator.consumption.v3.DiscoveryEngine;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;

import java.util.Objects;

import javax.sql.DataSource;

/** Explicit inactive factories. No manager, connection pool, thread, bean or grant is created. */
public final class V3ConsumptionDiscoveryComposition {
    private V3ConsumptionDiscoveryComposition() {}

    private static void effects(
            V3BootstrapGuard guard,
            ConsumptionDefinition definition,
            DataSource source,
            SpringAttemptTransactions owner,
            V3BootstrapGuard.Privileges privileges) {
        if (Objects.requireNonNull(privileges)
                != V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY)
            throw new IllegalArgumentException(
                    "Discovery requires an explicitly selected combined profile");
        if (!com.kartaguez.pocoma.domain.consumption.v3.ConsumptionCatalog.definitions().contains(definition)
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
            V3BootstrapGuard guard,
            ConsumptionDefinition definition,
            DataSource effectsSource,
            SpringAttemptTransactions effectsOwner,
            DataSource writeSource,
            SpringAttemptTransactions sourceReads,
            DiscoverySettings settings,
            V3BootstrapGuard.Privileges privileges) {
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
