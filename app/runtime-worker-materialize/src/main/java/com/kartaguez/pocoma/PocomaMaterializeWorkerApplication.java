package com.kartaguez.pocoma;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.core.env.Environment;
import java.util.*;
@SpringBootApplication(scanBasePackages="com.kartaguez.pocoma.runtime.bootstrap.empty",excludeName={"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration","org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration","org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration","org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration","org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"})
@Import({com.kartaguez.pocoma.compose.polling.materialize.v3.MaterializeComposeConfiguration.class})
public class PocomaMaterializeWorkerApplication {
    public static void main(String[] args){SpringApplication.run(PocomaMaterializeWorkerApplication.class,args);}
    @Bean static BeanFactoryPostProcessor hostingSelection(Environment env) {
        return factory -> {
            // Worker pools are private to the selected Compose, never unowned Spring resources.
            for(String name:factory.getBeanDefinitionNames()) {
                Class<?> type=factory.getType(name,false);
                if(type!=null && (javax.sql.DataSource.class.isAssignableFrom(type)
                        || org.springframework.transaction.PlatformTransactionManager.class.isAssignableFrom(type)
                        || jakarta.persistence.EntityManagerFactory.class.isAssignableFrom(type)))
                    throw new IllegalStateException("Unallocated worker authority bean: "+name);
            }
            var profiles = Set.of(env.getActiveProfiles());
            if (!profiles.equals(Set.of("v3-read-worker")))
                throw new IllegalStateException("Unsupported Runtime selection: " + profiles);
        };
    }
}
