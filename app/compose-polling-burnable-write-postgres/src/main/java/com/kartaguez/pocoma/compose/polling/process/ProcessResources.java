package com.kartaguez.pocoma.compose.polling.process;

import com.kartaguez.pocoma.contract.consumption.invocation.CapacityView;
import com.kartaguez.pocoma.contract.consumption.invocation.WorkerView;
import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition;
import com.kartaguez.pocoma.infra.postgres.core.bootstrap.BootstrapGuard;
import com.kartaguez.pocoma.infra.postgres.core.bootstrap.WorkerPostgresSupport;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.supra.polling.consumption.ConsumptionWorker;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.core.env.Environment;

/** Owns the technical DataSources, transaction owners, bootstrap guard, and lifecycle state for Process. */
public final class ProcessResources {
    public final HikariDataSource writeBusiness, writeControl;
    public final SpringAttemptTransactions writeEffects, writeTechnical;
    public final BootstrapGuard guard;
    private volatile ConsumptionWorker worker;
    private volatile CapacityView capacityView;
    private boolean closed;

    ProcessResources(ProcessSettings settings, Environment env, MeterRegistry registry) {
        var opened = new ArrayList<HikariDataSource>();
        try {
            writeBusiness = open(settings, settings.writeBusiness(), opened, registry);
            writeControl = open(settings, settings.writeControl(), opened, registry);
            writeEffects = owner(writeBusiness, 60);
            writeTechnical = owner(writeControl, 3);
            var target =
                    new BootstrapGuard.Target(
                            writeControl,
                            settings.writeControl().database(),
                            settings.writeControl().schema(),
                            settings.writeControl().username());
            String sourceRole =
                    env.getProperty(
                            "pocoma.v3.process.source-read-role",
                            env.getProperty(
                                    "pocoma.v3.pilot.read.username", "pocoma_write_source_read"));
            guard =
                    BootstrapGuard.processWorker(
                            target, sourceRole, settings.catalog(), settings.fixtureIdentity());
            for (var spec : List.of(settings.writeBusiness(), settings.writeControl())) {
                settings.validateTarget(spec, spec.business() ? writeBusiness : writeControl);
            }
            guard.validateLifecycleSource(
                    writeBusiness,
                    ConsumptionDefinition.Authority.WRITE,
                    BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
            guard.validateLifecycleSource(
                    writeControl,
                    ConsumptionDefinition.Authority.WRITE,
                    BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
            if (settings.identityAuthority().equals("real")) {
                String readEffectRole = env.getProperty("pocoma.v3.pilot.read.username");
                guard.validateIdentitySource(writeBusiness, readEffectRole);
                guard.validateIdentitySource(writeControl, readEffectRole);
            }
        } catch (RuntimeException | Error failure) {
            for (var pool : opened) {
                try {
                    pool.close();
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    private static SpringAttemptTransactions owner(HikariDataSource pool, int timeout) {
        return WorkerPostgresSupport.transactionOwner(pool, timeout);
    }

    private static HikariDataSource open(
            ProcessSettings settings,
            WorkerPostgresSupport.PoolSpec spec,
            List<HikariDataSource> opened,
            MeterRegistry registry) {
        var pool =
                new HikariDataSource(
                        WorkerPostgresSupport.buildHikariConfig(settings.workerName(), spec, false));
        if (registry != null) {
            pool.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(registry));
        }
        opened.add(pool);
        WorkerPostgresSupport.awaitPoolReserve(pool, spec.minimumIdle());
        return pool;
    }

    void bindWorker(ConsumptionWorker worker, CapacityView capacityView) {
        this.capacityView = capacityView;
        this.worker = worker;
    }

    public synchronized void closeIfSafe() {
        if (worker == null) {
            close();
            return;
        }
        var state = worker.snapshot().state();
        if (state == WorkerView.State.STARTING || state == WorkerView.State.STOPPED) {
            close();
        }
    }

    synchronized void close() {
        if (closed) {
            return;
        }
        writeControl.close();
        writeBusiness.close();
        closed = true;
    }

    public Optional<CapacityView> capacityView() {
        return Optional.ofNullable(capacityView);
    }

    public Optional<WorkerView.Snapshot> workerState() {
        return Optional.ofNullable(worker).map(ConsumptionWorker::snapshot);
    }
}
