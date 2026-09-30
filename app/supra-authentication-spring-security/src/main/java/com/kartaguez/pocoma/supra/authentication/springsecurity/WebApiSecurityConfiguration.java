package com.kartaguez.pocoma.supra.authentication.springsecurity;

import java.io.IOException;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
@ConditionalOnExpression("'${pocoma.command-admission.enabled:false}' == 'true' or "
		+ "'${pocoma.command-result-read.enabled:false}' == 'true' or '${pocoma.pot-read.enabled:false}' == 'true'")
public class WebApiSecurityConfiguration {
	@Bean
	SecurityFilterChain webApiSecurityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
		http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.csrf(csrf -> csrf.disable())
				.authorizeHttpRequests(requests -> requests
						.requestMatchers("/api/v1/commands", "/api/v1/command-results/**", "/api/v1/pots/**")
						.authenticated().anyRequest().permitAll())
				.oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults())
						.authenticationEntryPoint((request, response, exception) -> writeError(objectMapper,
								request, response, 401, "INVALID_TOKEN", "Authentication required")))
				.exceptionHandling(errors -> errors.accessDeniedHandler((request, response, exception) ->
						writeError(objectMapper, request, response, 403, "ACCESS_DENIED", "Access denied")));
		return http.build();
	}

	@Bean SpringSecurityExternalPrincipalAdapter springSecurityExternalPrincipalAdapter() {
		return new SpringSecurityExternalPrincipalAdapter();
	}
	@Bean AuthenticatedExternalPrincipalArgumentResolver authenticatedExternalPrincipalArgumentResolver(
			SpringSecurityExternalPrincipalAdapter adapter) {
		return new AuthenticatedExternalPrincipalArgumentResolver(adapter);
	}
	@Bean WebMvcConfigurer authenticatedExternalPrincipalWebMvcConfigurer(
			AuthenticatedExternalPrincipalArgumentResolver resolver) {
		return new WebMvcConfigurer() {
			@Override public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
				resolvers.add(resolver);
			}
		};
	}

	private static void writeError(ObjectMapper objectMapper, HttpServletRequest request,
			HttpServletResponse response, int status, String code, String message) throws IOException {
		response.setStatus(status); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		objectMapper.writeValue(response.getOutputStream(), new SecurityErrorResponse(
				code, message, status, request.getRequestURI()));
	}
	private record SecurityErrorResponse(String code, String message, int status, String path) {}
}
