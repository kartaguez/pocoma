package com.kartaguez.pocoma;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kartaguez.pocoma.orchestrator.command.admission.ExternalAuthorityPermissionTranslator;

@Configuration
public class WebAuthorizationConfiguration {
	@Bean
	ExternalAuthorityPermissionTranslator externalAuthorityPermissionTranslator() {
		return new ExternalAuthorityPermissionTranslator();
	}
}
