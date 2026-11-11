package com.kartaguez.pocoma.compose.polling.process.v3;
import com.kartaguez.pocoma.infra.postgres.core.v3.V3BootstrapGuard;
import com.kartaguez.pocoma.infra.observability.micrometer.v3.StageHandoffTracker;
import com.kartaguez.pocoma.infra.postgres.core.v3.*;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoverySettings;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.orchestrator.consumption.v3.DiscoveryEngine;
import com.kartaguez.pocoma.infra.postgres.write.v3.consumption.*;
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
                && !ProcessSettings.probeDefinitions().contains(definition))
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
            DiscoverySettings settings,
            V3BootstrapGuard.Privileges privileges) {
        return requests(guard,definition,source,owner,settings,privileges,com.kartaguez.pocoma.contract.consumption.observation.DiscoveryListener.NOOP);
    }

    public static DiscoveryEngine requests(
            V3BootstrapGuard guard,
            ConsumptionDefinition definition,
            DataSource source,
            SpringAttemptTransactions owner,
            DiscoverySettings settings,
            V3BootstrapGuard.Privileges privileges, com.kartaguez.pocoma.contract.consumption.observation.DiscoveryListener listener) {
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
                        : new JdbcCommandDiscoveryReferences(source, settings),
                listener);
    }

}
