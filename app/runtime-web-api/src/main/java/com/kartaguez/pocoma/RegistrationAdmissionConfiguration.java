package com.kartaguez.pocoma;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.kartaguez.pocoma.engine.admit.registration.AdmitRegistrationService;
import com.kartaguez.pocoma.engine.admit.registration.RegistrationRequestRecorder;
import com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunner;

@Configuration
@ConditionalOnProperty(prefix = "pocoma.registration-admission", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RegistrationAdmissionConfiguration {
    @Bean AdmitRegistrationService admitRegistrationService(RegistrationRequestRecorder requests,
            PlatformTransactionManager manager) {
        return new AdmitRegistrationService(requests,
                new SpringTransactionRunner(new TransactionTemplate(manager)), Clock.systemUTC());
    }
}
