package com.kartaguez.pocoma;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import com.kartaguez.pocoma.runtime.registrationresult.RegistrationResultRuntimeConfiguration;

@SpringBootApplication
@Import(RegistrationResultRuntimeConfiguration.class)
public class PocomaRegistrationResultConsumptionWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(PocomaRegistrationResultConsumptionWorkerApplication.class, args);
    }
}
