package com.kartaguez.pocoma.compose.polling.materialize;

import com.kartaguez.pocoma.contract.consumption.invocation.CapacityView;
import com.kartaguez.pocoma.contract.consumption.invocation.WorkerView;
import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition;
import com.kartaguez.pocoma.infra.postgres.core.bootstrap.BootstrapGuard;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.supra.polling.consumption.ConsumptionWorker;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Owns the technical DataSources, transaction owners, bootstrap guard, and lifecycle state for Materialize. */
public final class MaterializeResources {
    public final HikariDataSource writeSourceRead, readBusiness, readControl;
    public final SpringAttemptTransactions sourceReads, readEffects, readTechnical;
    public final BootstrapGuard guard;
    private volatile ConsumptionWorker worker;
    private volatile CapacityView capacityView;
    private boolean closed;

    MaterializeResources(MaterializeSettings settings, Environment env, MeterRegistry registry) {
        var opened = new ArrayList<HikariDataSource>();
        try {
            writeSourceRead = open(settings, settings.sourceRead(), opened, registry);
            readBusiness = open(settings, settings.readBusiness(), opened, registry);
            readControl = open(settings, settings.readControl(), opened, registry);
            sourceReads = owner(writeSourceRead, 60);
            readEffects = owner(readBusiness, 60);
            readTechnical = owner(readControl, 3);
            guard =
                    BootstrapGuard.materializeWorker(
                            new BootstrapGuard.Target(
                                    writeSourceRead,
                                    settings.sourceRead().database(),
                                    settings.sourceRead().schema(),
                                    settings.sourceRead().username()),
                            new BootstrapGuard.Target(
                                    readControl,
                                    settings.readControl().database(),
                                    settings.readControl().schema(),
                                    settings.readControl().username()),
                            settings.catalog());
            settings.validateTarget(settings.sourceRead(), writeSourceRead);
            settings.validateTarget(settings.readBusiness(), readBusiness);
            settings.validateTarget(settings.readControl(), readControl);
            guard.validateWriteSourceRead(writeSourceRead);
            guard.validateLifecycleSource(
                    readBusiness,
                    ConsumptionDefinition.Authority.READ,
                    BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
            guard.validateLifecycleSource(
                    readControl,
                    ConsumptionDefinition.Authority.READ,
                    BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
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
        var manager = new DataSourceTransactionManager(pool);
        manager.setNestedTransactionAllowed(false);
        return new SpringAttemptTransactions(pool, manager, timeout);
    }

    private static HikariDataSource open(
            MaterializeSettings settings,
            MaterializeSettings.Pool spec,
            List<HikariDataSource> opened,
            MeterRegistry registry) {
        var pool = new HikariDataSource(buildHikariConfig(settings, spec));
        if (registry != null) {
            pool.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(registry));
        }
        opened.add(pool);
        awaitPoolReserve(pool, spec.minimumIdle());
        return pool;
    }

    private static HikariConfig buildHikariConfig(
            MaterializeSettings settings, MaterializeSettings.Pool spec) {
        var config = new HikariConfig();
        config.setPoolName(settings.workerName() + "-" + spec.name());
        config.setJdbcUrl(spec.url());
        config.setReadOnly(spec.name().equals("write-source-read"));
        config.setUsername(spec.username());
        config.setPassword(spec.password());
        config.setMaximumPoolSize(spec.maximum());
        config.setMinimumIdle(spec.minimumIdle());
        config.setConnectionTimeout(1000);
        config.setValidationTimeout(500);
        config.setInitializationFailTimeout(5000);
        config.addDataSourceProperty("connectTimeout", "2");
        config.addDataSourceProperty("socketTimeout", spec.business() ? "70" : "5");
        config.addDataSourceProperty("ApplicationName", config.getPoolName());
        config.addDataSourceProperty(
                "options",
                spec.business()
                        ? "-c statement_timeout=60000 -c transaction_timeout=65000"
                        : "-c lock_timeout=500 -c statement_timeout=2000 -c"
                                + " transaction_timeout=3000");
        return config;
    }

    private static void awaitPoolReserve(HikariDataSource pool, int minimumIdle) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (pool.getHikariPoolMXBean().getIdleConnections() < minimumIdle
                && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("pool initialization interrupted");
            }
        }
        if (pool.getHikariPoolMXBean().getIdleConnections() < minimumIdle) {
            throw new IllegalStateException("physical pool reserve not established");
        }
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
        readBusiness.close();
        readControl.close();
        writeSourceRead.close();
        closed = true;
    }

    public Optional<CapacityView> capacityView() {
        return Optional.ofNullable(capacityView);
    }

    public Optional<WorkerView.Snapshot> workerState() {
        return Optional.ofNullable(worker).map(ConsumptionWorker::snapshot);
    }
}
