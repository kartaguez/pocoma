package com.kartaguez.pocoma;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.core.env.Environment;
import java.util.*;
@SpringBootApplication(scanBasePackages="com.kartaguez.pocoma.runtime.bootstrap.empty",excludeName={"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration","org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration","org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration","org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration","org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"})
@Import({com.kartaguez.pocoma.compose.web.write.v3.V3AdmissionHttpConfiguration.class,com.kartaguez.pocoma.compose.web.read.v3.V3ReadHttpConfiguration.class,com.kartaguez.pocoma.compose.polling.process.v3.ProcessComposeConfiguration.class,com.kartaguez.pocoma.compose.polling.materialize.v3.MaterializeComposeConfiguration.class})
public class PocomaRuntimeMonolithApplication {
    public static void main(String[] args){SpringApplication.run(PocomaRuntimeMonolithApplication.class,args);}
    @Bean static BeanFactoryPostProcessor hostingSelection(Environment env) {
        return factory -> {
            var allowed=Set.of("v3-http-admission","v3-http-read","v3-consumption-worker-dev","v3-read-worker");
            for(String profile:env.getActiveProfiles())if(!allowed.contains(profile))throw new IllegalStateException("Unsupported Runtime selection: "+profile);
            // Sharing observations is an explicit host decision; providers remain independently constructible.
            var names=List.of("webWriteHandoffs","webReadHandoffs","processHandoffs","materializeHandoffs");
            String selected=null;
            if(factory instanceof org.springframework.beans.factory.support.DefaultListableBeanFactory registry)
                for(String candidate:names)if(registry.containsBeanDefinition(candidate)) {
                    if(selected==null)selected=candidate;
                    else {registry.removeBeanDefinition(candidate);registry.registerAlias(selected,candidate);}
                }
        };
    }
    @Bean
    @Profile("!v3-http-admission & !v3-http-read")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type=org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
    org.springframework.security.web.SecurityFilterChain workerManagementSecurity(
            org.springframework.security.config.annotation.web.builders.HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(r -> r.requestMatchers("/actuator/health/**","/actuator/prometheus").permitAll()
                .anyRequest().denyAll()).build();
    }
}
