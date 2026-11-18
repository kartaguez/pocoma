package com.kartaguez.pocoma.compose.web.read;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import com.kartaguez.pocoma.contract.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.contract.readquery.BindingRead;
import com.kartaguez.pocoma.contract.readquery.CommandResultRead;
import com.kartaguez.pocoma.contract.readquery.IdentityResultRead;
import com.kartaguez.pocoma.contract.readquery.InvitationRead;
import com.kartaguez.pocoma.contract.readquery.ReadLimits;
import com.kartaguez.pocoma.contract.readquery.ReadPotResult;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.infra.postgres.read.maintenance.JdbcReadMaintenanceStatus;
import com.kartaguez.pocoma.infra.security.spring.HttpSecurityConfiguration;
import com.kartaguez.pocoma.infra.security.spring.InvitationConfiguration;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.projection.maintenance.ReadMaintenanceStatus;
import com.kartaguez.pocoma.supra.web.read.InvitationController;
import com.kartaguez.pocoma.supra.web.read.ReadController;
import com.kartaguez.pocoma.supra.web.read.ReadTransport;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

/** Explicit V3 HTTP assembly. Standalone WEB owns only a two-connection READ pool. */
@Configuration(proxyBeanMethods = false)
@Profile(ReadHttpConfiguration.PROFILE)
@Import({WebReadObservationConfiguration.class,
    HttpSecurityConfiguration.class,
    ReadController.class,
    InvitationController.class,
    StageHandoffController.class
})
public class ReadHttpConfiguration {
    private static final Duration[] READ_SLO_BUCKETS = {
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

    @Bean
    ReadTransport readTransport(
            Environment env,
            org.springframework.beans.factory.ObjectProvider<MeterRegistry> meterRegistry) {
        ReadTransport.Telemetry telemetry =
                (endpoint, status, durationNanos) -> {
                    var registry = meterRegistry.getIfAvailable();
                    if (registry != null) {
                        Tags tags = Tags.of("endpoint", endpoint, "status", String.valueOf(status));
                        Timer.builder("pocoma.v3.read.http.duration")
                                .tags(tags)
                                .publishPercentileHistogram()
                                .serviceLevelObjectives(READ_SLO_BUCKETS)
                                .register(registry)
                                .record(Duration.ofNanos(durationNanos));
                    }
                };
        int httpPermits = env.getProperty("pocoma.v3.http.read.http-permits", Integer.class, ReadTransport.HTTP_PERMITS);
        return new ReadTransport(
                Arrays.asList(env.getActiveProfiles()).contains("v3-http-admission"),
                telemetry,
                httpPermits);
    }

    @Bean
    FilterRegistrationBean<ReadTransport> readFilter(ReadTransport transport) {
        var registration = new FilterRegistrationBean<>(transport);
        registration.setOrder(-90);
        registration.addUrlPatterns("/api/v3/*");
        return registration;
    }


    public static final String PROFILE = "v3-http-read";

    @Bean
    static BeanFactoryPostProcessor httpPreflight(Environment env) {
        return new HttpPreflight(env);
    }

    public record HttpPreflight(Environment env) implements BeanFactoryPostProcessor {
        private static final Set<String> ADMISSION_ALLOWLIST =
                Set.of(
                        "com.kartaguez.pocoma.engine.admit.command.CommandAdmission",
                        "com.kartaguez.pocoma.engine.admit.identity.IdentityRequestAdmission",
                        "com.kartaguez.pocoma.supra.web.write.AdmissionController",
                        "com.kartaguez.pocoma.supra.web.write.AdmissionTransport");

        private static final Set<String> LEGACY_WEB_TYPES =
                Set.of(
                        "com.kartaguez.pocoma.PocomaWebRuntimeConfiguration",
                        "com.kartaguez.pocoma.runtime.web.authentication.WebApiSecurityConfiguration");

        @Override
        public void postProcessBeanFactory(
                org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory) {
            requireDisabledProperties();
            var profiles = Set.of(env.getActiveProfiles());
            rejectWorkerBeans(factory, profiles);
            if (!profiles.contains("v3-http-admission")) {
                rejectAdmissionBeans(factory);
            }
            rejectLegacyBeans(factory, profiles.contains("v3-http-admission"));
            env.getRequiredProperty("pocoma.v3.http.issuer");
            env.getRequiredProperty("pocoma.v3.http.audience");
            env.getRequiredProperty("pocoma.v3.http.jwk-set-uri");
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
                if (isWorkerBeanType(factory.getType(name, false))) {
                    throw new IllegalStateException(
                            "Workers and partition ownership forbidden on HTTP READ runtime");
                }
            }
        }

        private static boolean isWorkerBeanType(Class<?> type) {
            if (type == null) return false;
            String className = type.getName();
            return className.contains("WorkerCapacity")
                    || className.contains("ConsumptionWorker");
        }

        private static void rejectAdmissionBeans(
                org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory) {
            for (String name : factory.getBeanDefinitionNames()) {
                Class<?> type = factory.getType(name, false);
                if (type == null) continue;
                String val = type.getName();
                if (isAdmissionBean(name, val)) {
                    throw new IllegalStateException(
                            "Admission component forbidden on HTTP READ runtime: " + val);
                }
            }
        }

        private static boolean isAdmissionBean(String name, String val) {
            if (name.equals("admissionPool")) return true;
            if (val.startsWith("com.kartaguez.pocoma.supra.web.write.")) return true;
            return val.startsWith("com.kartaguez.pocoma.engine.admit.");
        }

        private static void rejectLegacyBeans(
                org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory,
                boolean admissionEnabled) {
            for (String name : factory.getBeanDefinitionNames()) {
                Class<?> type = factory.getType(name, false);
                if (type == null) continue;
                String value = type.getName();
                if (admissionEnabled && ADMISSION_ALLOWLIST.contains(value)) continue;
                if (isForbiddenLegacyWebType(value)) {
                    throw new IllegalStateException(
                            "legacy HTTP/admission component refused in V3");
                }
            }
        }

        private static boolean isForbiddenLegacyWebType(String value) {
            if (value.startsWith("com.kartaguez.pocoma.supra.web.") && !value.contains(".read.")) {
                return true;
            }
            if (value.startsWith("com.kartaguez.pocoma.engine.admit.")) return true;
            return LEGACY_WEB_TYPES.contains(value);
        }
    }

    @Bean(destroyMethod = "close")
    HikariDataSource httpReadPool(Environment env, Optional<MeterRegistry> meterRegistry) {
        var config = new HikariConfig();
        String p = "pocoma.v3.http.read.";
        config.setJdbcUrl(env.getRequiredProperty(p + "url"));
        config.setUsername(env.getRequiredProperty(p + "username"));
        config.setPassword(env.getRequiredProperty(p + "password"));
        config.setPoolName("v3-http-read");
        int sqlPermits = env.getProperty(p + "sql-permits", Integer.class, ReadFacadeComposition.Services.SQL_PERMITS);
        int maxPoolSize = env.getProperty(p + "maximum-pool-size", Integer.class, sqlPermits);
        int minIdle = env.getProperty(p + "minimum-idle", Integer.class, maxPoolSize);
        if (sqlPermits < 1 || maxPoolSize < sqlPermits) {
            throw new IllegalArgumentException("Invalid READ pool/SQL permit sizing");
        }
        if (minIdle < 0 || minIdle > maxPoolSize) {
            throw new IllegalArgumentException("Invalid READ pool/SQL permit sizing");
        }
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setConnectionTimeout(1000);
        config.setReadOnly(true);
        config.addDataSourceProperty("socketTimeout", "70");
        config.addDataSourceProperty("ApplicationName", "v3-http-read");
        var pool = new HikariDataSource(config);
        meterRegistry.ifPresent(r -> pool.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(r)));
        return pool;
    }

    /** Maintenance status must remain observable while both business query permits are held. */
    @Bean(destroyMethod = "close")
    // Web READ owns its SELECT-only status pool even when co-hosted with Materialize.
    HikariDataSource httpReadMaintenancePool(Environment env, Optional<MeterRegistry> registry) {
        String p="pocoma.v3.http.read.";
        var config=new HikariConfig();
        config.setJdbcUrl(env.getRequiredProperty(p+"url"));
        config.setUsername(env.getProperty(p+"maintenance.username",env.getRequiredProperty(p+"username")));
        config.setPassword(env.getProperty(p+"maintenance.password",env.getRequiredProperty(p+"password")));
        config.setPoolName("v3-http-read-maintenance");
        config.setMaximumPoolSize(1);config.setMinimumIdle(1);config.setReadOnly(true);
        config.setConnectionTimeout(1000);config.setValidationTimeout(500);
        config.addDataSourceProperty("connectTimeout","2");config.addDataSourceProperty("socketTimeout","5");
        config.addDataSourceProperty("ApplicationName","v3-http-read-maintenance");
        config.addDataSourceProperty("options","-c lock_timeout=200 -c statement_timeout=500 -c transaction_timeout=1000");
        var pool=new HikariDataSource(config);
        registry.ifPresent(r -> pool.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(r)));
        return pool;
    }

    @Bean
    ReadMaintenanceStatus httpReadMaintenanceAuthority(
            HikariDataSource httpReadMaintenancePool) {
        return new JdbcReadMaintenanceStatus(
                httpReadMaintenancePool);
    }

    @Bean
    ReadFacadeComposition.Services httpStandaloneServices(
            HikariDataSource httpReadPool,
            Environment env,
            Optional<MeterRegistry> meterRegistry) {
        int sqlPermits = env.getProperty("pocoma.v3.http.read.sql-permits", Integer.class, ReadFacadeComposition.Services.SQL_PERMITS);
        var manager = new DataSourceTransactionManager(httpReadPool);
        manager.setNestedTransactionAllowed(false);
        var owner = new SpringAttemptTransactions(httpReadPool, manager, 3);
        return ReadFacadeComposition.create(
                httpReadPool,
                owner,
                new ReadLimits(50, 100, 16_000_000),
                Set.of(),
                Optional::empty,
                true,
                meterRegistry.orElse(null),
                sqlPermits);
    }

    public record InvitationQueries(
            ReadFacadeComposition.Services services,
            com.kartaguez.pocoma.infra.security.spring.InvitationCryptography crypto)
            implements InvitationRead.Queries {
        public ReadPotResult<InvitationRead.Link> link(ExternalIdentity eui, UUID request) {
            return services.invitationLink(
                    eui,
                    request,
                    i -> {
                        if (crypto == null)
                            throw new IllegalStateException("Invitation keyring unavailable");
                        return crypto.decrypt(i);
                    });
        }

        public ReadPotResult<List<InvitationRead.Place>> places(
                ExternalIdentity eui, String binding, String token) {
            return services.invitationPlaces(eui, binding, token);
        }
    }

    @Bean
    InvitationRead.Queries httpInvitations(
            ReadFacadeComposition.Services services, Environment env) {
        return new InvitationQueries(services, InvitationConfiguration.keys(env));
    }

    @Bean
    BindingRead.Query httpBinding(ReadFacadeComposition.Services services) {
        return (eui, minimum) -> {
            var result = services.binding(eui, minimum);
            return result instanceof ReadPotResult.Available<BindingRead.Answer> a
                    ? a.value()
                    : new BindingRead.Answer(BindingRead.Status.UNAVAILABLE, null);
        };
    }

    @Bean
    CommandResultRead.Query httpCommandResult(ReadFacadeComposition.Services services) {
        return (id, eui) -> unwrapTerminalResult(services.commandResult(id, eui));
    }

    @Bean
    IdentityResultRead.Query httpIdentityResult(ReadFacadeComposition.Services services) {
        return (id, eui) -> unwrapTerminalResult(services.identityResult(id, eui));
    }

    @SuppressWarnings("unchecked")
    private static <T> Optional<T> unwrapTerminalResult(ReadPotResult<?> result) {
        if (result instanceof ReadPotResult.Available<?> a) {
            return (Optional<T>) a.value();
        }
        throw new ReadTransport.Failure(503);
    }

    @Bean
    FilterRegistrationBean<com.kartaguez.pocoma.supra.web.read.maintenance.ReadMaintenanceFilter> readMaintenanceFilter(
            @org.springframework.beans.factory.annotation.Qualifier("httpReadMaintenanceAuthority")
            ReadMaintenanceStatus authority,
            Environment env) {
        boolean admission = Arrays.asList(env.getActiveProfiles()).contains("v3-http-admission");
        var filter = new com.kartaguez.pocoma.supra.web.read.maintenance.ReadMaintenanceFilter(authority, admission);
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(-105);
        registration.addUrlPatterns("/api/v3/*");
        return registration;
    }

    @Bean
    com.kartaguez.pocoma.infra.observability.micrometer.ReadRebuildObservation readRebuildObservation(
            Optional<MeterRegistry> meterRegistry) {
        return new com.kartaguez.pocoma.infra.observability.micrometer.ReadRebuildObservation(
                meterRegistry.orElseGet(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new));
    }



    public static final class PrincipalResolver extends HttpSecurityConfiguration.PrincipalResolver {}

    public static AuthenticatedExternalPrincipal principal(org.springframework.security.oauth2.jwt.Jwt jwt) {
        return HttpSecurityConfiguration.principal(jwt);
    }
}
