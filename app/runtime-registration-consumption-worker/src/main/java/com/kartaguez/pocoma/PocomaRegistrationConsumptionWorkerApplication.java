package com.kartaguez.pocoma;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import com.kartaguez.pocoma.runtime.registration.RegistrationRuntimeConfiguration;

@SpringBootApplication
@Import(RegistrationRuntimeConfiguration.class)
public class PocomaRegistrationConsumptionWorkerApplication {
    public static void main(String[] args) { SpringApplication.run(PocomaRegistrationConsumptionWorkerApplication.class, args); }
}
