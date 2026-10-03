package com.kartaguez.pocoma;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.kartaguez.pocoma.engine.registration.GetRegistrationResultService;
import com.kartaguez.pocoma.engine.registration.RegistrationResultStore;

@Configuration
public class RegistrationResultReadConfiguration {
    @Bean GetRegistrationResultService getRegistrationResultService(RegistrationResultStore results) {
        return new GetRegistrationResultService(results);
    }
}
