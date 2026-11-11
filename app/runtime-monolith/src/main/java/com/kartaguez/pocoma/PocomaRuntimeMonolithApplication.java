package com.kartaguez.pocoma;

import com.kartaguez.pocoma.compose.polling.materialize.v3.MaterializeComposeConfiguration;
import com.kartaguez.pocoma.compose.polling.process.v3.ProcessComposeConfiguration;
import com.kartaguez.pocoma.compose.web.read.v3.V3ReadHttpConfiguration;
import com.kartaguez.pocoma.compose.web.write.v3.V3AdmissionHttpConfiguration;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;
import java.util.Set;

@SpringBootApplication(
        scanBasePackages = "com.kartaguez.pocoma.runtime.bootstrap.empty",
        excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
        })
@Import({
    V3AdmissionHttpConfiguration.class,
    V3ReadHttpConfiguration.class,
    ProcessComposeConfiguration.class,
    MaterializeComposeConfiguration.class
})
public class PocomaRuntimeMonolithApplication {
    public static void main(String[] args) {
        SpringApplication.run(PocomaRuntimeMonolithApplication.class, args);
    }

    @Bean
    static BeanFactoryPostProcessor hostingSelection(Environment env) {
        return factory -> {
            var selectedProfiles = Set.of(env.getActiveProfiles());
            var allowed = Set.of("v3-http-admission", "v3-http-read",
                    "v3-consumption-worker-dev", "v3-read-worker");
            if (!allowed.containsAll(selectedProfiles))
                throw new IllegalStateException("Unsupported Runtime selection: " + selectedProfiles);
            // A single host shares handoff observations between its selected Composes.
            var observations = List.of("webWriteHandoffs", "webReadHandoffs",
                    "processHandoffs", "materializeHandoffs");
            if (factory instanceof org.springframework.beans.factory.support.DefaultListableBeanFactory registry) {
                String selected = null;
                for (String candidate : observations) {
                    if (!registry.containsBeanDefinition(candidate)) continue;
                    if (selected == null) {
                        selected = candidate;
                    } else {
                        registry.removeBeanDefinition(candidate);
                        registry.registerAlias(selected, candidate);
                    }
                }
            }
        };
    }

    @Bean
    @Profile("!v3-http-admission & !v3-http-read")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain workerManagementSecurity(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(r -> r.requestMatchers(
                        "/actuator/health/**", "/actuator/prometheus").permitAll()
                .anyRequest().denyAll()).build();
    }
}
