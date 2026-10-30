package com.kartaguez.pocoma.runtime.consumption.v3;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.DiscoveryEngine;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.*;
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
        if (!V3ConsumptionCatalogConfiguration.definitions().contains(definition)
                && !V3ConsumptionWorkerSettings.probeDefinitions().contains(definition))
            throw new IllegalArgumentException("unknown pilot definition");
        Objects.requireNonNull(guard).validateLifecyclePrivileges(privileges);
        guard.validateLifecycleSource(source, definition.authority(), privileges);
        Objects.requireNonNull(owner).requireSource(source);
    }

    public static DiscoveryEngine requests(
            V3BootstrapGuard guard,
            ConsumptionDefinition definition,
            DataSource source,
            SpringAttemptTransactions owner,
            DiscoveryEngine.Settings settings,
            V3BootstrapGuard.Privileges privileges) {
        if (definition.family() != ConsumableFamily.COMMAND
                && definition.family() != ConsumableFamily.IDENTITY_REQUEST)
            throw new IllegalArgumentException(
                    "production request source not delivered: " + definition.family());
        effects(guard, definition, source, owner, privileges);
        return new DiscoveryEngine(
                definition,
                owner,
                new JdbcPartitionOwnershipStore(source),
                new JdbcDiscoveryAdmissionStore(source, settings),
                settings,
                definition.family() == ConsumableFamily.IDENTITY_REQUEST
                        ? new JdbcIdentityRequestDiscoveryReferences(source, settings)
                        : new JdbcCommandDiscoveryReferences(source, settings));
    }

    public static DiscoveryEngine events(
            V3BootstrapGuard guard,
            ConsumptionDefinition definition,
            DataSource effectsSource,
            SpringAttemptTransactions effectsOwner,
            DataSource writeSource,
            SpringAttemptTransactions sourceReads,
            DiscoveryEngine.Settings settings,
            V3BootstrapGuard.Privileges privileges) {
        if (definition.family() != ConsumableFamily.POT_EVENT
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
                r ->
                        definition.family() != ConsumableFamily.IDENTITY_EVENT
                                || r.subjectType().equals("EXTERNAL_USER_IDENTITY"));
    }
}
