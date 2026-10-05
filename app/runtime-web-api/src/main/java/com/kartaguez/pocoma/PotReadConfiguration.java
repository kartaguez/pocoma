package com.kartaguez.pocoma;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kartaguez.pocoma.engine.read.projection.port.ExactProjectionReadUseCase;
import com.kartaguez.pocoma.engine.read.pot.PotReads;
import com.kartaguez.pocoma.engine.read.pot.ReadPotUseCase;
import com.kartaguez.pocoma.engine.read.pot.ReadPotForExternalIdentityUseCase;
import com.kartaguez.pocoma.engine.read.pot.ReadPotForExternalIdentityService;
import com.kartaguez.pocoma.engine.read.currentbinding.GetCurrentBindingUseCase;
import com.kartaguez.pocoma.domain.authorization.ExternalAuthorityPermissionTranslator;

@Configuration
@ConditionalOnProperty(prefix = "pocoma.pot-read", name = "enabled", havingValue = "true")
public class PotReadConfiguration {
	@Bean
	ReadPotUseCase readPotUseCase(ExactProjectionReadUseCase projections) {
		return PotReads.create(projections);
	}
    @Bean
    ReadPotForExternalIdentityUseCase readPotForExternalIdentityUseCase(ReadPotUseCase pots,
            GetCurrentBindingUseCase bindings) {
        return new ReadPotForExternalIdentityService(pots, bindings,
                new ExternalAuthorityPermissionTranslator());
    }
}
