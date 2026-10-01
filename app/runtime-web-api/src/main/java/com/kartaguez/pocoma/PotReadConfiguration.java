package com.kartaguez.pocoma;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kartaguez.pocoma.engine.port.in.projection.read.ExactProjectionReadUseCase;
import com.kartaguez.pocoma.engine.pot.read.PotReads;
import com.kartaguez.pocoma.engine.pot.read.ReadPotUseCase;

@Configuration
@ConditionalOnProperty(prefix = "pocoma.pot-read", name = "enabled", havingValue = "true")
public class PotReadConfiguration {
	@Bean
	ReadPotUseCase readPotUseCase(ExactProjectionReadUseCase projections) {
		return PotReads.create(projections);
	}
}
