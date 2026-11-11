package com.kartaguez.pocoma;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.core.env.Environment;
import java.util.*;
@SpringBootApplication(scanBasePackages="com.kartaguez.pocoma.runtime.bootstrap.empty",excludeName={"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration","org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration","org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration","org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration","org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"})
@Import({com.kartaguez.pocoma.compose.web.read.v3.V3ReadHttpConfiguration.class})
public class PocomaWebReadApplication {
    public static void main(String[] args){SpringApplication.run(PocomaWebReadApplication.class,args);}
    @Bean static BeanFactoryPostProcessor hostingSelection(Environment env) {
        return factory -> {
            var profiles = Set.of(env.getActiveProfiles());
            if (!profiles.equals(Set.of("v3-http-read")))
                throw new IllegalStateException("Unsupported Runtime selection: " + profiles);
        };
    }
}
