package com.kartaguez.pocoma;

import com.kartaguez.pocoma.runtime.task.consumption.CanonicalProjectionTaskRuntimeConfiguration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.*;

@SpringBootApplication(scanBasePackages = "com.kartaguez.pocoma.runtime.bootstrap")
@Import({
    PocomaTaskConsumptionWorkerApplication.Legacy.class,
    com.kartaguez.pocoma.runtime.consumption.v3.V3ConsumptionCatalogConfiguration.class,
    com.kartaguez.pocoma.runtime.consumption.v3.V3ConsumptionWorkerConfiguration.class
})
public class PocomaTaskConsumptionWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(PocomaTaskConsumptionWorkerApplication.class, args);
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("!v3-consumption-pilot & !v3-consumption-worker-dev & !v3-read-worker")
    @ComponentScan(
            basePackages = "com.kartaguez.pocoma",
            excludeFilters = {
                // This runtime never owns HTTP, including on a test classpath containing the adapter.
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.kartaguez\\.pocoma\\.supra\\.http\\..*"),
                @ComponentScan.Filter(
                        type = FilterType.CUSTOM,
                        classes = org.springframework.boot.context.TypeExcludeFilter.class),
                @ComponentScan.Filter(
                        type = FilterType.CUSTOM,
                        classes =
                                org.springframework.boot.autoconfigure
                                        .AutoConfigurationExcludeFilter.class)
            })
    @Import(CanonicalProjectionTaskRuntimeConfiguration.class)
    public static class Legacy {}
}
