package com.kartaguez.pocoma;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import com.kartaguez.pocoma.runtime.binding.BindingRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.command.consumption.CommandConsumptionRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.event.consumption.EventConsumptionRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.registration.RegistrationRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.registrationresult.RegistrationResultRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.result.CommandResultRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.task.consumption.CanonicalProjectionTaskRuntimeConfiguration;

@SpringBootApplication
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
public class PocomaRuntimeMonolithApplication {
    public static void main(String[] args) {
        SpringApplication.run(PocomaRuntimeMonolithApplication.class, args);
    }
}
