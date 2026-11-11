package com.kartaguez.pocoma.runtime.consumption.v3;
import com.kartaguez.pocoma.infra.security.spring.v3.InvitationCryptography;
import com.kartaguez.pocoma.infra.security.spring.v3.V3HttpSecurityConfiguration;
import com.kartaguez.pocoma.contract.readquery.IdentityResultRead;
import com.kartaguez.pocoma.contract.readquery.CommandResultRead;
import com.kartaguez.pocoma.contract.readquery.BindingRead;
import com.kartaguez.pocoma.contract.readquery.InvitationRead;
import com.kartaguez.pocoma.contract.readquery.PotRead;
import com.kartaguez.pocoma.contract.readquery.ReadLimitsV3;
import com.kartaguez.pocoma.contract.readquery.ReadPotV3Result;

import com.kartaguez.pocoma.contract.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.domain.projection.v3.PotBalance;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.engine.read.results.v3.GetCommandTerminalResult;
import com.kartaguez.pocoma.engine.read.identity.v3.GetCurrentBindingV3;
import com.kartaguez.pocoma.engine.read.pot.v3.*;
import com.kartaguez.pocoma.engine.read.results.v3.GetIdentityTerminalResult;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.supra.web.read.v3.*;
import com.zaxxer.hikari.*;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.MethodParameter;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.*;
import java.util.*;

/** Explicit V3 HTTP assembly. Standalone WEB owns only a two-connection READ pool. */
@Configuration(proxyBeanMethods = false)
@Profile(V3ReadHttpConfiguration.PROFILE)
@Import({V3ObservabilityConfiguration.class, 
    V3HttpSecurityConfiguration.class,
    V3ReadController.class,
    V3InvitationController.class,
    V3StageHandoffController.class
})
public class V3ReadHttpConfiguration {
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
    V3ReadTransport v3ReadTransport(
            Environment env,
            org.springframework.beans.factory.ObjectProvider<MeterRegistry> meterRegistry) {
        V3ReadTransport.Telemetry telemetry =
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
        return new V3ReadTransport(
                Arrays.asList(env.getActiveProfiles()).contains("v3-http-admission"),
                telemetry);
    }

    @Bean
    FilterRegistrationBean<V3ReadTransport> v3ReadFilter(V3ReadTransport transport) {
        var registration = new FilterRegistrationBean<>(transport);
        registration.setOrder(-90);
        registration.addUrlPatterns("/api/v3/*");
        return registration;
    }


    public static final String PROFILE = "v3-http-read";

    @Bean
    static BeanFactoryPostProcessor v3HttpPreflight(Environment env) {
        return new HttpPreflight(env);
    }

    public record HttpPreflight(Environment env) implements BeanFactoryPostProcessor {
        @Override
        public void postProcessBeanFactory(
                org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory) {
            if (env.getProperty("spring.flyway.enabled", Boolean.class, true)
                    || env.getProperty("spring.jpa.open-in-view", Boolean.class, true)
                    || env.getProperty("management.health.db.enabled", Boolean.class, true))
                throw new IllegalStateException(
                        "V3 HTTP requires migrations, OSIV and DB health disabled");
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
                        "Workers and partition ownership forbidden on HTTP READ runtime");
            }
            if (!Arrays.asList(env.getActiveProfiles()).contains("v3-http-admission")) {
                for (String name : factory.getBeanDefinitionNames()) {
                    Class<?> type = factory.getType(name, false);
                    if (type == null) continue;
                    String val = type.getName();
                    if (name.equals("v3AdmissionPool")
                            || val.startsWith("com.kartaguez.pocoma.supra.web.write.v3.")
                            || val.startsWith("com.kartaguez.pocoma.engine.admit.")) {
                        throw new IllegalStateException(
                                "Admission component forbidden on HTTP READ runtime: " + val);
                    }
                }
            }
            for (String name : factory.getBeanDefinitionNames()) {
                Class<?> type = factory.getType(name, false);
                if (type == null) continue;
                String value = type.getName();
                boolean admission =
                        Arrays.asList(env.getActiveProfiles()).contains("v3-http-admission")
                                && Set.of(
                                                "com.kartaguez.pocoma.engine.admit.command.v3.AdmitCommandV3",
                                                "com.kartaguez.pocoma.engine.admit.identity.v3.AdmitIdentityRequestV3",
                                                "com.kartaguez.pocoma.engine.admit.identity.v3.AdmitLinkProposal",
                                                "com.kartaguez.pocoma.supra.web.write.v3.V3LinkProposalController",
                                                "com.kartaguez.pocoma.supra.web.write.v3.V3AdmissionController",
                                                "com.kartaguez.pocoma.supra.web.write.v3.V3AdmissionTransport")
                                        .contains(value);
                if (!admission
                        && ((value.startsWith("com.kartaguez.pocoma.supra.web.")
                                        && !value.contains(".read.v3."))
                                || value.startsWith("com.kartaguez.pocoma.engine.admit.")
                                || value.equals(
                                        "com.kartaguez.pocoma.PocomaWebRuntimeConfiguration")
                                || value.equals(
                                        "com.kartaguez.pocoma.runtime.web.authentication.WebApiSecurityConfiguration")))
                    throw new IllegalStateException(
                            "legacy HTTP/admission component refused in V3");
            }
            env.getRequiredProperty("pocoma.v3.http.issuer");
            env.getRequiredProperty("pocoma.v3.http.audience");
            env.getRequiredProperty("pocoma.v3.http.jwk-set-uri");
        }
    }

    @Bean(destroyMethod = "close")
    @Profile("!v3-read-worker")
    HikariDataSource v3HttpReadPool(Environment env, Optional<MeterRegistry> meterRegistry) {
        var config = new HikariConfig();
        String p = "pocoma.v3.http.read.";
        config.setJdbcUrl(env.getRequiredProperty(p + "url"));
        config.setUsername(env.getRequiredProperty(p + "username"));
        config.setPassword(env.getRequiredProperty(p + "password"));
        config.setPoolName("v3-http-read");
        config.setMaximumPoolSize(V3ReadFacadeComposition.Services.SQL_PERMITS);
        config.setMinimumIdle(V3ReadFacadeComposition.Services.SQL_PERMITS);
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
    @Profile("!v3-read-worker")
    HikariDataSource v3HttpReadMaintenancePool(Environment env, Optional<MeterRegistry> registry) {
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
    @Profile("!v3-read-worker")
    com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadMaintenanceAuthority v3HttpReadMaintenanceAuthority(
            HikariDataSource v3HttpReadMaintenancePool) {
        return new com.kartaguez.pocoma.runtime.consumption.v3.maintenance.JdbcReadMaintenanceAuthority(
                v3HttpReadMaintenancePool);
    }

    @Bean
    @Profile("!v3-read-worker")
    V3ReadFacadeComposition.Services v3HttpStandaloneServices(
            HikariDataSource v3HttpReadPool,
            Optional<MeterRegistry> meterRegistry) {
        var manager = new DataSourceTransactionManager(v3HttpReadPool);
        manager.setNestedTransactionAllowed(false);
        var owner = new SpringAttemptTransactions(v3HttpReadPool, manager, 3);
        return V3ReadFacadeComposition.create(
                v3HttpReadPool,
                owner,
                new ReadLimitsV3(50, 100, 16_000_000),
                Set.of(),
                Optional::empty,
                true,
                meterRegistry.orElse(null));
    }

    /** Optional READ linkage belongs to the HTTP assembly, not to the shared Services type. */
    public record PotQueries(V3ReadFacadeComposition.Services delegate)
            implements PotRead.Queries {
        public ReadPotV3Result<ReadPotV3Result.Pot> current(
                ExternalIdentity eui, String token, UUID id, long minimum) {
            return delegate.current(eui, token, id, minimum);
        }

        public ReadPotV3Result<ReadPotV3Result.Pot> at(
                ExternalIdentity eui, String token, UUID id, long version) {
            return delegate.at(eui, token, id, version);
        }

        public ReadPotV3Result<PotBalance> balance(
                ExternalIdentity eui, String token, UUID id, long version) {
            return delegate.balance(eui, token, id, version);
        }

        public ReadPotV3Result<List<ReadPotV3Result.Expense>> expenses(
                ExternalIdentity eui,
                String token,
                UUID id,
                long version,
                List<ReadPotV3Result.ExpenseRef> references) {
            return delegate.expenses(eui, token, id, version, references);
        }

        public ReadPotV3Result<List<UUID>> visible(
                ExternalIdentity eui, String token, UUID after, int limit) {
            return delegate.visible(eui, token, after, limit);
        }
    }

    public record InvitationQueries(
            V3ReadFacadeComposition.Services services,
            com.kartaguez.pocoma.infra.security.spring.v3.InvitationCryptography crypto)
            implements InvitationRead.Queries {
        public ReadPotV3Result<InvitationRead.Link> link(ExternalIdentity eui, UUID request) {
            return services.invitationLink(
                    eui,
                    request,
                    i -> {
                        if (crypto == null)
                            throw new IllegalStateException("Invitation keyring unavailable");
                        return crypto.decrypt(i);
                    });
        }

        public ReadPotV3Result<List<InvitationRead.Place>> places(
                ExternalIdentity eui, String binding, String token) {
            return services.invitationPlaces(eui, binding, token);
        }
    }

    @Bean
    InvitationRead.Queries v3HttpInvitations(
            V3ReadFacadeComposition.Services services, Environment env) {
        return new InvitationQueries(services, V3InvitationConfiguration.keys(env));
    }

    @Bean
    PotRead.Queries v3HttpPot(V3ReadFacadeComposition.Services services) {
        return new PotQueries(services);
    }

    @Bean
    BindingRead.Query v3HttpBinding(V3ReadFacadeComposition.Services services) {
        return (eui, minimum) -> {
            var result = services.binding(eui, minimum);
            return result instanceof ReadPotV3Result.Available<BindingRead.Answer> a
                    ? a.value()
                    : new BindingRead.Answer(BindingRead.Status.UNAVAILABLE, null);
        };
    }

    @Bean
    CommandResultRead.Query v3HttpCommandResult(V3ReadFacadeComposition.Services services) {
        return (id, eui) -> {
            var result = services.commandResult(id, eui);
            if (result instanceof ReadPotV3Result.Available<?> a) {
                @SuppressWarnings("unchecked")
                var value =
                        (Optional<
                                        com.kartaguez.pocoma.domain.projection.v3.CommandTerminalResult>)
                                a.value();
                return value;
            }
            throw new V3ReadTransport.Failure(503);
        };
    }

    @Bean
    IdentityResultRead.Query v3HttpIdentityResult(
            V3ReadFacadeComposition.Services services) {
        return (id, eui) -> {
            var result = services.identityResult(id, eui);
            if (result instanceof ReadPotV3Result.Available<?> a) {
                @SuppressWarnings("unchecked")
                var value =
                        (Optional<
                                        com.kartaguez.pocoma.domain.projection.v3.IdentityTerminalResult>)
                                a.value();
                return value;
            }
            throw new V3ReadTransport.Failure(503);
        };
    }

    @Bean
    FilterRegistrationBean<com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadMaintenanceFilter> v3ReadMaintenanceFilter(
            com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadMaintenanceAuthority authority,
            Environment env) {
        boolean admission = Arrays.asList(env.getActiveProfiles()).contains("v3-http-admission");
        var filter = new com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadMaintenanceFilter(authority, admission);
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(-105);
        registration.addUrlPatterns("/api/v3/*");
        return registration;
    }

    @Bean
    com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadRebuildObservation v3ReadRebuildObservation(
            Optional<MeterRegistry> meterRegistry) {
        return new com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadRebuildObservation(
                meterRegistry.orElseGet(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new));
    }

    @Bean
    com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadinessOracle v3ReadinessOracle() {
        return new com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadinessOracle();
    }

    public static final class PrincipalResolver extends V3HttpSecurityConfiguration.PrincipalResolver {}

    public static AuthenticatedExternalPrincipal principal(org.springframework.security.oauth2.jwt.Jwt jwt) {
        return V3HttpSecurityConfiguration.principal(jwt);
    }
}
