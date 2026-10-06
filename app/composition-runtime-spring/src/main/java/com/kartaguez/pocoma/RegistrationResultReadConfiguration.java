package com.kartaguez.pocoma;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.kartaguez.pocoma.engine.read.registrationresult.GetRegistrationResultService;
import com.kartaguez.pocoma.engine.read.registrationresult.RegistrationResultStore;

public class RegistrationResultReadConfiguration {
    @Bean GetRegistrationResultService getRegistrationResultService(RegistrationResultStore results) {
        return new GetRegistrationResultService(results);
    }
}
