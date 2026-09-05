package com.kartaguez.pocoma.supra.authentication.springsecurity;

import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.kartaguez.pocoma.orchestrator.command.admission.InvalidAuthenticatedExternalPrincipalException;
import com.kartaguez.pocoma.orchestrator.command.admission.model.AuthenticatedExternalPrincipal;

public final class AuthenticatedExternalPrincipalArgumentResolver implements HandlerMethodArgumentResolver {
	private final SpringSecurityExternalPrincipalAdapter adapter;

	public AuthenticatedExternalPrincipalArgumentResolver(SpringSecurityExternalPrincipalAdapter adapter) {
		this.adapter = adapter;
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.getParameterType().equals(AuthenticatedExternalPrincipal.class);
	}

	@Override
	public Object resolveArgument(
			MethodParameter parameter,
			ModelAndViewContainer container,
			NativeWebRequest request,
			WebDataBinderFactory binderFactory) {
		if (!(request.getUserPrincipal() instanceof JwtAuthenticationToken authentication)) {
			throw new InvalidAuthenticatedExternalPrincipalException("An authenticated JWT principal is required");
		}
		return adapter.adapt(authentication);
	}
}
