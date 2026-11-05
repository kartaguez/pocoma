package com.kartaguez.pocoma.runtime.consumption.v3;

import com.kartaguez.pocoma.contracts.command.v3.*;
import com.kartaguez.pocoma.engine.admit.command.v3.AdmitCommandV3;
import com.kartaguez.pocoma.engine.admit.registration.v3.AdmitIdentityRequestV3;
import com.kartaguez.pocoma.engine.admit.registration.v3.AdmitLinkProposal;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.supra.http.write.v3.*;
import com.zaxxer.hikari.*;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Clock;
import java.util.*;

/** Explicit HTTP-only activation. No admission allocation on COMMAND or READ-only roots. */
@Configuration(proxyBeanMethods = false)
@Profile(V3AdmissionHttpConfiguration.PROFILE)
@Import({V3AdmissionController.class, V3LinkProposalController.class})
public class V3AdmissionHttpConfiguration {
    public static final String PROFILE = "v3-http-admission";
    private static final String PREFIX = "pocoma.v3.http.admission.";

    @Bean
    static BeanFactoryPostProcessor v3AdmissionPreflight(Environment env) {
        return new Preflight(env);
    }

    public record Preflight(Environment env) implements BeanFactoryPostProcessor {
        @Override
        public void postProcessBeanFactory(
                org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory) {
            if (!Arrays.asList(env.getActiveProfiles()).contains(V3ReadHttpConfiguration.PROFILE))
                throw new IllegalStateException(
                        "Admission requires V3 authenticated HTTP assembly");
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
            return new AdmissionProtection(
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

    @Bean(destroyMethod = "close")
    HikariDataSource v3AdmissionPool(Environment env) {
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
    V3AdmissionTransport v3AdmissionTransport(Environment env) {
        return new V3AdmissionTransport(limits(env));
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
