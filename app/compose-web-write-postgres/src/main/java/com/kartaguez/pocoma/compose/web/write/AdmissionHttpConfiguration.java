package com.kartaguez.pocoma.compose.web.write;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import com.kartaguez.pocoma.contract.writeadmission.AdmissionProtection;
import com.kartaguez.pocoma.engine.admit.command.CommandAdmission;
import com.kartaguez.pocoma.engine.admit.identity.IdentityRequestAdmission;
import com.kartaguez.pocoma.infra.observability.micrometer.StageHandoffTracker;
import com.kartaguez.pocoma.infra.postgres.write.admission.AdmissionSqlAuthority;
import com.kartaguez.pocoma.infra.postgres.write.admission.AdmissionSqlTransactions;
import com.kartaguez.pocoma.infra.postgres.write.admission.HmacAdmissionProtection;
import com.kartaguez.pocoma.infra.postgres.write.admission.JdbcAdmissionStore;
import com.kartaguez.pocoma.infra.security.spring.HttpSecurityConfiguration;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.supra.web.write.AdmissionController;
import com.kartaguez.pocoma.supra.web.write.AdmissionTransport;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

/** Explicit HTTP-only activation. No admission allocation on COMMAND or READ-only roots. */
@Configuration(proxyBeanMethods = false)
@Profile(AdmissionHttpConfiguration.PROFILE)
@Import({WebWriteObservationConfiguration.class, HttpSecurityConfiguration.class, AdmissionController.class})
public class AdmissionHttpConfiguration {
    public static final String PROFILE = "v3-http-admission";
    private static final String PREFIX = "pocoma.v3.http.admission.";

    @Bean
    com.kartaguez.pocoma.contract.writeadmission.AdmissionObserver admissionObserver(@org.springframework.beans.factory.annotation.Qualifier("webWriteHandoffs") StageHandoffTracker tracker) {
        return tracker::onCommandAdmitted;
    }

    @Bean
    static BeanFactoryPostProcessor admissionPreflight(Environment env) {
        return new Preflight(env);
    }

    public record Preflight(Environment env) implements BeanFactoryPostProcessor {
        @Override
        public void postProcessBeanFactory(
                org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory) {
            requireDisabledProperties();
            var profiles = Set.of(env.getActiveProfiles());
            rejectWorkerBeans(factory, profiles);
            if (!profiles.contains("v3-http-read")) {
                rejectReadBeans(factory);
            }
            limits(env);
            protection(env);
            for (String key : List.of("url", "username", "password", "database", "schema")) {
                env.getRequiredProperty(PREFIX + key);
            }
            requireDistinctAdmissionRole();
        }

        private void requireDisabledProperties() {
            for (String key :
                    List.of(
                            "spring.flyway.enabled",
                            "spring.jpa.open-in-view",
                            "management.health.db.enabled")) {
                if (env.getProperty(key, Boolean.class, true)) {
                    throw new IllegalStateException(
                            "V3 HTTP requires migrations, OSIV and DB health disabled");
                }
            }
        }

        private static void rejectWorkerBeans(
                org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory,
                Set<String> profiles) {
            if (profiles.contains("v3-consumption-worker-dev")
                    || profiles.contains("v3-read-worker")) {
                return;
            }
            for (String name : factory.getBeanDefinitionNames()) {
                Class<?> type = factory.getType(name, false);
                if (type == null) continue;
                String className = type.getName();
                if (className.contains("WorkerCapacity")
                        || className.contains("ConsumptionWorker")) {
                    throw new IllegalStateException(
                            "Workers and partition ownership forbidden on HTTP WRITE runtime");
                }
            }
        }

        private static void rejectReadBeans(
                org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory) {
            for (String name : factory.getBeanDefinitionNames()) {
                Class<?> type = factory.getType(name, false);
                if (type == null) continue;
                String val = type.getName();
                if (isReadBean(name, val)) {
                    throw new IllegalStateException(
                            "READ component forbidden on HTTP WRITE runtime: " + val);
                }
            }
        }

        private static boolean isReadBean(String name, String val) {
            if (name.equals("httpReadPool")) return true;
            if (val.startsWith("com.kartaguez.pocoma.supra.web.read.ReadController")) return true;
            return val.startsWith("com.kartaguez.pocoma.supra.web.read.InvitationController");
        }

        private void requireDistinctAdmissionRole() {
            String role = env.getRequiredProperty(PREFIX + "username");
            for (String key :
                    List.of(
                            "pocoma.v3.worker.write-business.username",
                            "pocoma.v3.worker.write-control.username",
                            "pocoma.v3.process.write-business.username",
                            "pocoma.v3.process.write-control.username",
                            "pocoma.v3.http.read.username",
                            "pocoma.v3.http.read.maintenance.username")) {
                if (role.equals(env.getProperty(key))) {
                    throw new IllegalStateException("Admission SQL role must be distinct");
                }
            }
        }
    }

    static AdmissionTransport.Limits limits(Environment env) {
        return new AdmissionTransport.Limits(
                env.getProperty(PREFIX + "command-bytes", Integer.class, 262144),
                        env.getProperty(PREFIX + "identity-bytes", Integer.class, 16384),
                env.getProperty(PREFIX + "depth", Integer.class, 16),
                        env.getProperty(PREFIX + "text-bytes", Integer.class, 4096),
                env.getProperty(PREFIX + "rational-digits", Integer.class, 1024),
                        env.getProperty(PREFIX + "capacity", Integer.class, 1));
    }

    static AdmissionProtection protection(Environment env) {
        try {
            String current = env.getRequiredProperty(PREFIX + "comparison-current");
            var keys = new HashMap<String, byte[]>();
            for (String id :
                    env.getRequiredProperty(PREFIX + "comparison-key-ids").split(",", -1)) {
                if (id.isBlank()
                        || keys.putIfAbsent(
                                        id,
                                        Base64.getDecoder()
                                                .decode(
                                                        env.getRequiredProperty(
                                                                PREFIX + "comparison-keys." + id)))
                                != null) throw new IllegalArgumentException();
            }
            return new HmacAdmissionProtection(
                    env.getRequiredProperty(PREFIX + "lookup-key-id"),
                    Base64.getDecoder().decode(env.getRequiredProperty(PREFIX + "lookup-key")),
                    current,
                    keys);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Missing or incompatible admission protection keys");
        }
    }

    private static final Duration[] BUCKETS = {
        Duration.ofMillis(1),
        Duration.ofMillis(5),
        Duration.ofMillis(10),
        Duration.ofMillis(50),
        Duration.ofMillis(100),
        Duration.ofMillis(500),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        Duration.ofSeconds(30),
        Duration.ofSeconds(60),
        Duration.ofSeconds(120)
    };

    @Bean(destroyMethod = "close")
    HikariDataSource admissionPool(Environment env, Optional<MeterRegistry> meterRegistry) {
        var c = new HikariConfig();
        var l = limits(env);
        c.setJdbcUrl(env.getRequiredProperty(PREFIX + "url"));
        c.setUsername(env.getRequiredProperty(PREFIX + "username"));
        c.setPassword(env.getRequiredProperty(PREFIX + "password"));
        c.setPoolName("v3-http-admission");
        c.setMaximumPoolSize(l.capacity());
        c.setMinimumIdle(l.capacity());
        c.setConnectionTimeout(1000);
        c.setInitializationFailTimeout(1000);
        c.addDataSourceProperty("connectTimeout", "2");
        c.addDataSourceProperty("socketTimeout", "5");
        c.addDataSourceProperty("ApplicationName", "v3-http-admission");
        c.addDataSourceProperty(
                "options",
                "-c statement_timeout=2000 -c lock_timeout=500 -c transaction_timeout=3000");
        var pool = new HikariDataSource(c);
        meterRegistry.ifPresent(r -> pool.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(r)));
        try {
            AdmissionSqlAuthority.verify(
                    pool,
                    env.getRequiredProperty(PREFIX + "database"),
                    env.getRequiredProperty(PREFIX + "schema"),
                    env.getRequiredProperty(PREFIX + "username"));
            return pool;
        } catch (RuntimeException invalid) {
            pool.close();
            throw new IllegalStateException("Admission SQL authority refused");
        }
    }

    public static final class Resources {
        final AdmissionSqlTransactions owner;
        final JdbcAdmissionStore store;
        final AdmissionProtection protection;

        Resources(HikariDataSource pool, AdmissionProtection protection) {
            var manager = new DataSourceTransactionManager(pool);
            manager.setNestedTransactionAllowed(false);
            var physicalOwner = new SpringAttemptTransactions(pool, manager, 3);
            owner = new AdmissionSqlTransactions(pool, physicalOwner);
            store = new JdbcAdmissionStore(pool, physicalOwner);
            this.protection = protection;
        }

        @Override
        public String toString() {
            return "AdmissionResources[REDACTED]";
        }
    }

    @Bean
    Resources admissionResources(HikariDataSource admissionPool, Environment env) {
        return new Resources(admissionPool, protection(env));
    }

    @Bean
    CommandAdmission commandAdmission(Resources r, Environment env) {
        return new CommandAdmission(
                r.store, r.owner, r.protection, Clock.systemUTC(), limits(env).textBytes());
    }

    @Bean
    IdentityRequestAdmission identityAdmission(Resources r, Environment env) {
        return new IdentityRequestAdmission(
                r.store, r.owner, r.protection, Clock.systemUTC(), limits(env).textBytes());
    }

    @Bean
    AdmissionTransport admissionTransport(Environment env, Optional<MeterRegistry> meterRegistry) {
        var limits = limits(env);
        var registry = meterRegistry.orElse(null);
        AdmissionTransport.Telemetry telemetry = (endpoint, status, durationNanos) -> {
            if (registry != null) {
                Tags tags = Tags.of("endpoint", endpoint, "status", String.valueOf(status));
                registry.counter("pocoma.v3.admission.requests", tags).increment();
                Timer.builder("pocoma.v3.admission.http.duration")
                        .tags(tags)
                        .publishPercentileHistogram()
                        .serviceLevelObjectives(BUCKETS)
                        .register(registry)
                        .record(Duration.ofNanos(durationNanos));
            }
        };
        var transport = new AdmissionTransport(limits, telemetry);
        if (registry != null) {
            Gauge.builder("pocoma.v3.admission.capacity.available", transport, AdmissionTransport::availableCapacity)
                    .tag("endpoint", "admission")
                    .register(registry);
        }
        return transport;
    }

    @Bean
    FilterRegistrationBean<AdmissionTransport> admissionFilter(AdmissionTransport transport) {
        var r = new FilterRegistrationBean<>(transport);
        r.setOrder(-89);
        r.addUrlPatterns("/api/v3/commands", "/api/v3/identity/requests");
        return r;
    }
}
