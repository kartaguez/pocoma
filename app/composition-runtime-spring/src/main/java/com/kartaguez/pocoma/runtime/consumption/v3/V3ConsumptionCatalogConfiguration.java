package com.kartaguez.pocoma.runtime.consumption.v3;

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
public class V3ConsumptionCatalogConfiguration {
    public static List<ConsumptionDefinition> definitions() {
        return List.of(
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("98f7268d-d340-581c-b83d-010926223e14")),
                        ConsumableFamily.COMMAND,
                        Authority.WRITE),
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("2999e75c-272d-5afe-a966-c9364ef021fe")),
                        ConsumableFamily.IDENTITY_REQUEST,
                        Authority.WRITE),
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a")),
                        ConsumableFamily.POT_EVENT,
                        Authority.READ),
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("190a93c6-b8af-5ac7-8001-72e2ec53c2df")),
                        ConsumableFamily.POT_EVENT,
                        Authority.READ),
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("b4d04cce-b5da-5d1b-9698-5ff9af9a46fa")),
                        ConsumableFamily.POT_EVENT,
                        Authority.READ),
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("924cd9e3-435d-5804-9d1e-c6aecadb9ab4")),
                        ConsumableFamily.INVITATION_EVENT,
                        Authority.READ),
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("7b8d2838-bf7a-5117-868a-e5c14b4c0221")),
                        ConsumableFamily.IDENTITY_EVENT,
                        Authority.READ),
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("63664303-d33e-5d42-8cce-c4dae8023311")),
                        ConsumableFamily.COMMAND_COMPLETION_EVENT,
                        Authority.READ),
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("1d8ff246-358f-5ddd-a7f5-416efcfd7445")),
                        ConsumableFamily.IDENTITY_COMPLETION_EVENT,
                        Authority.READ));
    }

    @Bean
    static BeanFactoryPostProcessor rejectIncompatiblePilotDefinitions() {
        return factory -> {
            for (String name : factory.getBeanDefinitionNames()) {
                Class<?> type = factory.getType(name, false);
                if (type != null) V3BootstrapGuard.requireInertType(type);
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
                if (bean != null) V3BootstrapGuard.requireInertType(bean.getClass());
            }
            guard.validate();
        };
    }
}
