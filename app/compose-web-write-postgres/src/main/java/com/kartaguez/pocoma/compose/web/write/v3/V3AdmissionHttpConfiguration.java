package com.kartaguez.pocoma.compose.web.write.v3;
import com.kartaguez.pocoma.infra.security.spring.v3.V3HttpSecurityConfiguration;
import com.kartaguez.pocoma.infra.observability.micrometer.v3.StageHandoffTracker;
import com.kartaguez.pocoma.infra.postgres.write.v3.HmacAdmissionProtection;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionProtection;

import com.kartaguez.pocoma.contract.writeadmission.*;
import com.kartaguez.pocoma.engine.admit.command.v3.AdmitCommandV3;
import com.kartaguez.pocoma.engine.admit.identity.v3.AdmitIdentityRequestV3;
import com.kartaguez.pocoma.engine.admit.identity.v3.AdmitLinkProposal;
import com.kartaguez.pocoma.infra.postgres.write.v3.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.supra.web.write.v3.*;
import com.zaxxer.hikari.*;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.util.*;

/** Explicit HTTP-only activation. No admission allocation on COMMAND or READ-only roots. */
@Configuration(proxyBeanMethods = false)
@Profile(V3AdmissionHttpConfiguration.PROFILE)
@Import({WebWriteObservationConfiguration.class, V3HttpSecurityConfiguration.class, V3AdmissionController.class, V3LinkProposalController.class})
public class V3AdmissionHttpConfiguration {
    public static final String PROFILE = "v3-http-admission";
    private static final String PREFIX = "pocoma.v3.http.admission.";

    @Bean
    com.kartaguez.pocoma.contract.writeadmission.AdmissionObserver v3AdmissionObserver(@org.springframework.beans.factory.annotation.Qualifier("webWriteHandoffs") StageHandoffTracker tracker) {
        return tracker::onCommandAdmitted;
    }

    @Bean
    static BeanFactoryPostProcessor v3AdmissionPreflight(Environment env) {
        return new Preflight(env);
    }

    public record Preflight(Environment env) implements BeanFactoryPostProcessor {
        @Override
        public void postProcessBeanFactory(
                org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory) {
            boolean root =
                    Arrays.stream(factory.getBeanDefinitionNames())
                            .map(n -> factory.getType(n, false))
                            .filter(Objects::nonNull)
                            .map(Class::getName)
                            .anyMatch(
                                    n ->
                                            Set.of(
                                                            "com.kartaguez.pocoma.PocomaWebApiApplication",
                                                            "com.kartaguez.pocoma.PocomaRuntimeMonolithApplication")
                                                    .contains(n));
            if (!root) throw new IllegalStateException("Admission requires WEB or Monolith root");
            boolean monolith = Arrays.stream(factory.getBeanDefinitionNames())
                    .map(n -> factory.getType(n, false))
                    .filter(Objects::nonNull)
                    .anyMatch(t -> t.getName().equals("com.kartaguez.pocoma.PocomaRuntimeMonolithApplication"));
            if (!monolith && Arrays.stream(factory.getBeanDefinitionNames())
                    .map(n -> factory.getType(n, false))
                    .filter(Objects::nonNull)
                    .anyMatch(
                            t ->
                                    t.getName().contains("WorkerCapacity")
                                            || t.getName().contains("V3ConsumptionWorker"))) {
                throw new IllegalStateException(
                        "Workers and partition ownership forbidden on HTTP WRITE runtime");
            }
            if (!Arrays.asList(env.getActiveProfiles()).contains("v3-http-read")) {
                for (String name : factory.getBeanDefinitionNames()) {
                    Class<?> type = factory.getType(name, false);
                    if (type == null) continue;
                    String val = type.getName();
                    if (val.startsWith("com.kartaguez.pocoma.supra.web.read.v3.V3ReadController")
                            || val.startsWith("com.kartaguez.pocoma.supra.web.read.v3.V3InvitationController")
                            || name.equals("v3HttpReadPool")) {
                        throw new IllegalStateException(
                                "READ component forbidden on HTTP WRITE runtime: " + val);
                    }
                }
            }
            limits(env);
            protection(env);
            linkTtl(env);
            for (String key : List.of("url", "username", "password", "database", "schema"))
                env.getRequiredProperty(PREFIX + key);
            String role = env.getRequiredProperty(PREFIX + "username");
            for (String key :
                    List.of(
                            "pocoma.v3.worker.write-business.username",
                            "pocoma.v3.worker.write-control.username",
                            "pocoma.v3.http.read.username"))
                if (role.equals(env.getProperty(key)))
                    throw new IllegalStateException("Admission SQL role must be distinct");
        }
    }

    static V3AdmissionTransport.Limits limits(Environment env) {
        return new V3AdmissionTransport.Limits(
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

    static java.time.Duration linkTtl(Environment env) {
        long seconds = env.getProperty(PREFIX + "link-proposal-ttl-seconds", Long.class, 600L);
        if (seconds < 1 || seconds > 86400) throw new IllegalStateException("Invalid Link proposal TTL");
        return java.time.Duration.ofSeconds(seconds);
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
    HikariDataSource v3AdmissionPool(Environment env, Optional<MeterRegistry> meterRegistry) {
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
    Resources v3AdmissionResources(HikariDataSource v3AdmissionPool, Environment env) {
        return new Resources(v3AdmissionPool, protection(env));
    }

    @Bean
    AdmitCommandV3 v3CommandAdmission(Resources r, Environment env) {
        return new AdmitCommandV3(
                r.store, r.owner, r.protection, Clock.systemUTC(), limits(env).textBytes());
    }

    @Bean
    AdmitIdentityRequestV3 v3IdentityAdmission(Resources r, Environment env) {
        return new AdmitIdentityRequestV3(
                r.store, r.owner, r.protection, Clock.systemUTC(), limits(env).textBytes());
    }

    @Bean
    V3AdmissionTransport v3AdmissionTransport(Environment env, Optional<MeterRegistry> meterRegistry) {
        var limits = limits(env);
        var registry = meterRegistry.orElse(null);
        V3AdmissionTransport.Telemetry telemetry = (endpoint, status, durationNanos) -> {
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
        var transport = new V3AdmissionTransport(limits, telemetry);
        if (registry != null) {
            Gauge.builder("pocoma.v3.admission.capacity.available", transport, V3AdmissionTransport::availableCapacity)
                    .tag("endpoint", "admission")
                    .register(registry);
        }
        return transport;
    }

    @Bean
    AdmitLinkProposal v3LinkProposalAdmission(Resources r, Environment env) {
        return new AdmitLinkProposal(r.store, r.owner, r.protection, Clock.systemUTC(),
                linkTtl(env), limits(env).textBytes());
    }

    @Bean
    FilterRegistrationBean<V3AdmissionTransport> v3AdmissionFilter(V3AdmissionTransport transport) {
        var r = new FilterRegistrationBean<>(transport);
        r.setOrder(-89);
        r.addUrlPatterns("/api/v3/commands", "/api/v3/identity/requests",
                "/api/v3/identity/link-requests", "/api/v3/identity/link-requests/*");
        return r;
    }
}
