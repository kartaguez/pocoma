package com.kartaguez.pocoma.compose.polling.process;
import com.kartaguez.pocoma.infra.postgres.core.BootstrapGuard;
import com.kartaguez.pocoma.infra.observability.micrometer.StageHandoffTracker;
import com.kartaguez.pocoma.infra.postgres.core.*;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoverySettings;

import com.kartaguez.pocoma.domain.consumption.*;
import com.kartaguez.pocoma.orchestrator.consumption.DiscoveryEngine;
import com.kartaguez.pocoma.infra.postgres.write.consumption.*;
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
                && !ProcessSettings.probeDefinitions().contains(definition))
            throw new IllegalArgumentException("unknown pilot definition");
        Objects.requireNonNull(guard).validateLifecyclePrivileges(privileges);
        guard.validateLifecycleSource(source, definition.authority(), privileges);
        Objects.requireNonNull(owner).requireSource(source);
    }

    public static DiscoveryEngine requests(
            BootstrapGuard guard,
            ConsumptionDefinition definition,
            DataSource source,
            SpringAttemptTransactions owner,
            DiscoverySettings settings,
            BootstrapGuard.Privileges privileges) {
        return requests(guard,definition,source,owner,settings,privileges,com.kartaguez.pocoma.contract.consumption.observation.DiscoveryListener.NOOP);
    }

    public static DiscoveryEngine requests(
            BootstrapGuard guard,
            ConsumptionDefinition definition,
            DataSource source,
            SpringAttemptTransactions owner,
            DiscoverySettings settings,
            BootstrapGuard.Privileges privileges, com.kartaguez.pocoma.contract.consumption.observation.DiscoveryListener listener) {
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
