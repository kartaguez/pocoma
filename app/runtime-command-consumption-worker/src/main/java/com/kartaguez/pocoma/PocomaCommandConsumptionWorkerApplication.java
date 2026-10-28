package com.kartaguez.pocoma;

import com.kartaguez.pocoma.runtime.command.consumption.CommandConsumptionRuntimeConfiguration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication(scanBasePackages = "com.kartaguez.pocoma.runtime.bootstrap")
@Import({
    PocomaCommandConsumptionWorkerApplication.Legacy.class,
    com.kartaguez.pocoma.runtime.consumption.v3.V3ConsumptionCatalogConfiguration.class,
    com.kartaguez.pocoma.runtime.consumption.v3.V3ConsumptionWorkerConfiguration.class
})
public class PocomaCommandConsumptionWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(PocomaCommandConsumptionWorkerApplication.class, args);
    }

    /** Legacy scanning and processing are mutually exclusive with the inert pilot. */
    @org.springframework.context.annotation.Profile(
            "!v3-consumption-pilot & !v3-consumption-worker-dev")
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.ComponentScan(
            basePackages = "com.kartaguez.pocoma",
            excludeFilters = {
                @org.springframework.context.annotation.ComponentScan.Filter(
                        type = org.springframework.context.annotation.FilterType.CUSTOM,
                        classes = org.springframework.boot.context.TypeExcludeFilter.class),
                @org.springframework.context.annotation.ComponentScan.Filter(
                        type = org.springframework.context.annotation.FilterType.CUSTOM,
                        classes =
                                org.springframework.boot.autoconfigure
                                        .AutoConfigurationExcludeFilter.class)
            })
    @Import(CommandConsumptionRuntimeConfiguration.class)
    public static class Legacy {}
}
