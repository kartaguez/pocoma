package com.kartaguez.pocoma.compose.polling.process;
import com.kartaguez.pocoma.infra.postgres.core.BootstrapGuard;
import com.kartaguez.pocoma.contract.consumption.spi.LifecycleSettings;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionEngine;
import com.kartaguez.pocoma.orchestrator.consumption.WorkerCapacity;

import com.kartaguez.pocoma.engine.write.identity.ExecuteIdentityRequest;
import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation;
import com.kartaguez.pocoma.infra.postgres.write.identity.*;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.zaxxer.hikari.HikariDataSource;

import java.util.UUID;

/** Explicit Identity factory using the existing Business/Control resources and L2 owner. */
public final class IdentityRequestComposition {
    private IdentityRequestComposition() {}

    public static ConsumptionEngine assemble(
            HikariDataSource business,
            SpringAttemptTransactions effects,
            HikariDataSource control,
            SpringAttemptTransactions technical,
            BootstrapGuard guard,
            LifecycleSettings settings,
            WorkerCapacity capacity,
            ConsumptionObservation observation) {
        effects.requireSource(business);
        guard.validateIdentitySource(business);
        guard.validateIdentitySource(control);
        var requests = new JdbcIdentityRequestStore(business, effects);
        requests.requireRetiredLinksDrained();
        var participant =
                new ExecuteIdentityRequest(
                        requests,
                        new JdbcIdentityRequestObservation(business),
                        new JdbcIdentityAuthority(business, effects),
                        new SecureBindingTokenGenerator()::generate,
                        UUID::randomUUID);
        return ConsumptionLifecycleComposition.assemble(
                guard,
                ProcessSettings.definition("IdentityRequest"),
                business,
                effects,
                participant,
                control,
                technical,
                settings,
                capacity,
                ConsumptionLifecycleComposition::classify,
                observation);
    }
}
