package com.kartaguez.pocoma.supra.http.rest.spring.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfiguration {

	public static final String USER_ID_HEADER = "X-User-Id";
	public static final String USER_SCOPES_HEADER = "X-User-Scopes";
	public static final String BEARER_AUTH = "bearerAuth";

	@Bean
	OpenAPI pocomaOpenAPI() {
		return new OpenAPI()
				.info(new Info()
						.title("Pocoma Command API")
						.version("v1"))
				.components(new Components()
						.addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
								.type(SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.bearerFormat("JWT"))
						.addSecuritySchemes(USER_ID_HEADER, new SecurityScheme()
								.type(SecurityScheme.Type.APIKEY)
								.in(SecurityScheme.In.HEADER)
								.name(USER_ID_HEADER))
						.addSecuritySchemes(USER_SCOPES_HEADER, new SecurityScheme()
								.type(SecurityScheme.Type.APIKEY)
								.in(SecurityScheme.In.HEADER)
								.name(USER_SCOPES_HEADER)));
	}
}
