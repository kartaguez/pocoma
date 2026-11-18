package com.kartaguez.pocoma.runtime.command;
import java.util.List;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import com.kartaguez.pocoma.domain.consumption.ConsumptionCatalog;
import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition;
import com.kartaguez.pocoma.infra.postgres.core.bootstrap.BootstrapGuard;

/** Explicit opt-in; no worker, transaction owner, seed or migration at runtime. */
@Configuration(proxyBeanMethods = false)
@Profile("v3-consumption-pilot")
public class InertCatalogTestConfiguration {
    public static List<ConsumptionDefinition> definitions() { return ConsumptionCatalog.definitions(); }

    @Bean
    static BeanFactoryPostProcessor rejectIncompatiblePilotDefinitions() {
        return factory -> {
            for (String name : factory.getBeanDefinitionNames()) {
                Class<?> type = factory.getType(name, false);
                if (type != null && type != InertCatalogTestConfiguration.class
                        && type != BootstrapGuard.class
                        && !name.equals("rejectIncompatiblePilotDefinitions")
                        && !name.equals("validateBothPilotAuthorities"))
                    BootstrapGuard.requireInertType(type);
            }
        };
    }

    @Bean
    BootstrapGuard bootstrapGuard(Environment environment) {
        return new BootstrapGuard(
                BootstrapGuard.Target.from(environment, "write"),
                BootstrapGuard.Target.from(environment, "read"),
                definitions());
    }

    @Bean
    SmartInitializingSingleton validateBothPilotAuthorities(
            BootstrapGuard guard,
            org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory) {
        return () -> {
            for (String name : factory.getSingletonNames()) {
                Object bean = factory.getSingleton(name);
                if (bean != null && !(bean instanceof InertCatalogTestConfiguration)
                        && !(bean instanceof BootstrapGuard)
                        && !name.equals("rejectIncompatiblePilotDefinitions")
                        && !name.equals("validateBothPilotAuthorities"))
                    BootstrapGuard.requireInertType(bean.getClass());
            }
            guard.validate();
        };
    }
}
