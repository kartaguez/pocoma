package com.kartaguez.pocoma.runtime.command.v3;
import com.kartaguez.pocoma.infra.postgres.core.v3.V3BootstrapGuard;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition.Authority;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.UUID;

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
                        && type != V3BootstrapGuard.class
                        && !name.equals("rejectIncompatiblePilotDefinitions")
                        && !name.equals("validateBothPilotAuthorities"))
                    V3BootstrapGuard.requireInertType(type);
            }
        };
    }

    @Bean
    V3BootstrapGuard v3BootstrapGuard(Environment environment) {
        return new V3BootstrapGuard(
                V3BootstrapGuard.Target.from(environment, "write"),
                V3BootstrapGuard.Target.from(environment, "read"),
                definitions());
    }

    @Bean
    SmartInitializingSingleton validateBothPilotAuthorities(
            V3BootstrapGuard guard,
            org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory) {
        return () -> {
            for (String name : factory.getSingletonNames()) {
                Object bean = factory.getSingleton(name);
                if (bean != null && !(bean instanceof InertCatalogTestConfiguration)
                        && !(bean instanceof V3BootstrapGuard)
                        && !name.equals("rejectIncompatiblePilotDefinitions")
                        && !name.equals("validateBothPilotAuthorities"))
                    V3BootstrapGuard.requireInertType(bean.getClass());
            }
            guard.validate();
        };
    }
}
