package com.kartaguez.pocoma;

import com.kartaguez.pocoma.runtime.binding.BindingRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.command.consumption.CommandConsumptionRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.event.consumption.EventConsumptionRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.registration.RegistrationRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.registrationresult.RegistrationResultRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.result.CommandResultRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.task.consumption.CanonicalProjectionTaskRuntimeConfiguration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication(scanBasePackages = "com.kartaguez.pocoma.runtime.bootstrap")
@Import({
    PocomaRuntimeMonolithApplication.Legacy.class,
    com.kartaguez.pocoma.runtime.consumption.v3.V3ConsumptionCatalogConfiguration.class
})
public class PocomaRuntimeMonolithApplication {
    public static void main(String[] args) {
        SpringApplication.run(PocomaRuntimeMonolithApplication.class, args);
    }

    /** Legacy scanning and processing are mutually exclusive with the inert pilot. */
    @org.springframework.context.annotation.Profile("!v3-consumption-pilot")
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
    @Import({
        PocomaWebRuntimeConfiguration.class,
        RegistrationRuntimeConfiguration.class,
        RegistrationResultRuntimeConfiguration.class,
        BindingRuntimeConfiguration.class,
        CommandConsumptionRuntimeConfiguration.class,
        CommandResultRuntimeConfiguration.class,
        EventConsumptionRuntimeConfiguration.class,
        CanonicalProjectionTaskRuntimeConfiguration.class
    })
    public static class Legacy {}
}
