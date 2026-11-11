package com.kartaguez.pocoma.infra.security.spring.v3;

import com.kartaguez.pocoma.contract.authentication.AuthenticatedExternalPrincipal;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.MethodParameter;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Shared authenticated HTTP assembly for V3 HTTP runtimes (WRITE and READ).
 * Configures Tomcat limits, RS256 JWT decoding, statutory security filtering and principal resolution.
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(
        excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
        })
@Profile({"v3-http-read", "v3-http-admission"})
public class V3HttpSecurityConfiguration {
    private static void securityError(jakarta.servlet.http.HttpServletResponse response,int status) throws java.io.IOException {
        String code=status==401?"INVALID_TOKEN":"NOT_FOUND";
        byte[] body=("{\"code\":\""+code+"\",\"message\":\"Request unavailable\",\"traceId\":\""+UUID.randomUUID()+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        response.resetBuffer();response.setStatus(status);response.setContentType("application/json");
        response.setHeader("Cache-Control","no-store");response.setContentLength(body.length);
        response.getOutputStream().write(body);response.flushBuffer();
    }


    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> v3HttpServerBounds() {
        return server ->
                server.addConnectorCustomizers(
                        connector -> {
                            var protocol =
                                    (org.apache.coyote.http11.AbstractHttp11Protocol<?>)
                                            connector.getProtocolHandler();
                            protocol.setMaxThreads(4);
                            protocol.setMaxConnections(16);
                            protocol.setAcceptCount(4);
                            protocol.setConnectionTimeout(5000);
                            protocol.setKeepAliveTimeout(5000);
                            protocol.setMaxHttpRequestHeaderSize(16_384);
                            protocol.setCompression("off");
                        });
    }

    @Bean
    JwtDecoder v3ReadJwtDecoder(Environment env) {
        var decoder =
                NimbusJwtDecoder.withJwkSetUri(
                                env.getRequiredProperty("pocoma.v3.http.jwk-set-uri"))
                        .jwsAlgorithm(SignatureAlgorithm.RS256)
                        .build();
        var conversion = MappedJwtClaimSetConverter.withDefaults(Map.of());
        decoder.setClaimSetConverter(
                raw -> {
                    // The default converter synthesizes iat from exp. V3 requires attested claims.
                    if (!(raw.get("iss") instanceof String issuer)
                            || issuer.isBlank()
                            || !(raw.get("sub") instanceof String subject)
                            || subject.isBlank()
                            || raw.get("iat") == null
                            || raw.get("exp") == null
                            || !(raw.get("auth_time") instanceof Number auth))
                        throw new BadJwtException("Incomplete authenticated claims");
                    try {
                        if (new java.math.BigDecimal(auth.toString()).longValueExact() < 0)
                            throw new ArithmeticException("negative auth_time");
                    } catch (ArithmeticException invalid) {
                        throw new BadJwtException("Invalid authenticated claims");
                    }
                    return conversion.convert(raw);
                });
        OAuth2TokenValidator<Jwt> claims =
                jwt -> {
                    boolean valid =
                            jwt.getSubject() != null
                                    && !jwt.getSubject().isBlank()
                                    && jwt.getAudience() != null
                                    && jwt.getAudience()
                                            .contains(
                                                    env.getRequiredProperty(
                                                            "pocoma.v3.http.audience"))
                                    && jwt.getIssuedAt() != null
                                    && jwt.getExpiresAt() != null;
                    try {
                        principal(jwt);
                    } catch (RuntimeException invalid) {
                        valid = false;
                    }
                    return valid
                            ? OAuth2TokenValidatorResult.success()
                            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
                };
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        new JwtTimestampValidator(Duration.ZERO),
                        new JwtIssuerValidator(env.getRequiredProperty("pocoma.v3.http.issuer")),
                        claims));
        return decoder;
    }

    @Bean
    SecurityFilterChain v3ReadSecurity(HttpSecurity http, JwtDecoder v3ReadJwtDecoder)
            throws Exception {
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(c -> c.disable())
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers("/actuator/**")
                                        .permitAll()
                                        .requestMatchers("/api/v3/**")
                                        .authenticated()
                                        .anyRequest()
                                        .denyAll())
                .oauth2ResourceServer(
                        o ->
                                o.jwt(j -> j.decoder(v3ReadJwtDecoder))
                                        .authenticationEntryPoint(
                                                (request, response, error) ->
                                                        securityError(response, 401)))
                .exceptionHandling(
                        e ->
                                e.accessDeniedHandler(
                                        (request, response, error) ->
                                                securityError(response, 404)));
        return http.build();
    }

    @Bean
    PrincipalResolver v3PrincipalResolver() {
        return new PrincipalResolver();
    }

    public static class PrincipalResolver
            implements WebMvcConfigurer, HandlerMethodArgumentResolver {
        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(this);
        }

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType() == AuthenticatedExternalPrincipal.class;
        }

        @Override
        public Object resolveArgument(
                MethodParameter parameter,
                ModelAndViewContainer mav,
                NativeWebRequest request,
                WebDataBinderFactory binder) {
            if (!(SecurityContextHolder.getContext().getAuthentication()
                    instanceof JwtAuthenticationToken token))
                throw new org.springframework.security.authentication.BadCredentialsException("Invalid authenticated principal");
            try {
                return principal(token.getToken());
            } catch (RuntimeException invalid) {
                throw new org.springframework.security.authentication.BadCredentialsException("Invalid authenticated principal");
            }
        }
    }

    public static AuthenticatedExternalPrincipal principal(Jwt jwt) {
        Object claim = jwt.getClaim("auth_time");
        Instant auth =
                claim instanceof Instant i
                        ? i
                        : claim instanceof Number n ? Instant.ofEpochSecond(n.longValue()) : null;
        if (jwt.getIssuer() == null
                || jwt.getSubject() == null
                || jwt.getSubject().isBlank()
                || auth == null
                || jwt.getIssuedAt() == null
                || jwt.getExpiresAt() == null
                || auth.isAfter(jwt.getIssuedAt())
                || !jwt.getIssuedAt().isBefore(jwt.getExpiresAt()))
            throw new IllegalArgumentException("incomplete authenticated claims");
        return new AuthenticatedExternalPrincipal(
                jwt.getIssuer().toString(),
                jwt.getSubject(),
                auth,
                jwt.getIssuedAt(),
                jwt.getExpiresAt(),
                Set.of());
    }
}
