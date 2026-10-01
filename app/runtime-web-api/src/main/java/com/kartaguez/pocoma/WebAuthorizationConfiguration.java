package com.kartaguez.pocoma;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kartaguez.pocoma.domain.authorization.ExternalAuthorityPermissionTranslator;

@Configuration
public class WebAuthorizationConfiguration {
	@Bean
	ExternalAuthorityPermissionTranslator externalAuthorityPermissionTranslator() {
		return new ExternalAuthorityPermissionTranslator();
	}
}
