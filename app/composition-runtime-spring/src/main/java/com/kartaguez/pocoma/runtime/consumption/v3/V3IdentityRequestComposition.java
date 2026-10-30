package com.kartaguez.pocoma.runtime.consumption.v3;

import com.kartaguez.pocoma.engine.consume.registration.v3.ExecuteIdentityRequest;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionObservation;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.zaxxer.hikari.HikariDataSource;

import java.util.UUID;

/** Explicit Identity factory using the existing Business/Control resources and L2 owner. */
public final class V3IdentityRequestComposition {
    private V3IdentityRequestComposition() {}

    public static ConsumptionEngine assemble(
            HikariDataSource business,
            SpringAttemptTransactions effects,
            HikariDataSource control,
            SpringAttemptTransactions technical,
            V3BootstrapGuard guard,
            LifecycleSettings settings,
            WorkerCapacity capacity,
            ConsumptionObservation observation) {
        effects.requireSource(business);
        guard.validateIdentitySource(business);
        guard.validateIdentitySource(control);
        var participant =
                new ExecuteIdentityRequest(
                        new JdbcIdentityRequestStore(business, effects),
                        new JdbcIdentityRequestObservation(business),
                        new JdbcIdentityAuthority(business, effects),
                        new SecureBindingTokenGenerator()::generate,
                        UUID::randomUUID);
        return V3ConsumptionLifecycleComposition.assemble(
                guard,
                V3ConsumptionWorkerSettings.definition("IdentityRequest"),
                business,
                effects,
                participant,
                control,
                technical,
                settings,
                capacity,
                V3ConsumptionLifecycleComposition::classify,
                observation);
    }
}
