package com.kartaguez.pocoma.infra.security.spring;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.MethodParameter;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.kartaguez.pocoma.contract.authentication.AuthenticatedExternalPrincipal;

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
public class HttpSecurityConfiguration {
    private static void securityError(jakarta.servlet.http.HttpServletResponse response,int status) throws java.io.IOException {
        String code=status==401?"INVALID_TOKEN":"NOT_FOUND";
        byte[] body=("{\"code\":\""+code+"\",\"message\":\"Request unavailable\",\"traceId\":\""+UUID.randomUUID()+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        response.resetBuffer();response.setStatus(status);response.setContentType("application/json");
        response.setHeader("Cache-Control","no-store");response.setContentLength(body.length);
        response.getOutputStream().write(body);response.flushBuffer();
    }


    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> httpServerBounds() {
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
    JwtDecoder readJwtDecoder(Environment env) {
        var decoder =
                NimbusJwtDecoder.withJwkSetUri(
                                env.getRequiredProperty("pocoma.v3.http.jwk-set-uri"))
                        .jwsAlgorithm(SignatureAlgorithm.RS256)
                        .build();
        var conversion = MappedJwtClaimSetConverter.withDefaults(Map.of());
        decoder.setClaimSetConverter(
                raw -> {
                    requireAttestedClaims(raw);
                    return conversion.convert(raw);
                });
        String expectedAudience = env.getRequiredProperty("pocoma.v3.http.audience");
        OAuth2TokenValidator<Jwt> claims =
                jwt -> validateJwtClaims(jwt, expectedAudience);
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        new JwtTimestampValidator(Duration.ZERO),
                        new JwtIssuerValidator(env.getRequiredProperty("pocoma.v3.http.issuer")),
                        claims));
        return decoder;
    }

    private static void requireAttestedClaims(Map<String, Object> raw) {
        // The default converter synthesizes iat from exp. V3 requires attested claims.
        requireNonBlankClaim(raw, "iss");
        requireNonBlankClaim(raw, "sub");
        if (raw.get("iat") == null || raw.get("exp") == null) {
            throw new BadJwtException("Incomplete authenticated claims");
        }
        requireValidAuthTime(raw.get("auth_time"));
    }

    private static void requireNonBlankClaim(Map<String, Object> raw, String name) {
        if (!(raw.get(name) instanceof String val) || val.isBlank()) {
            throw new BadJwtException("Incomplete authenticated claims");
        }
    }

    private static void requireValidAuthTime(Object authClaim) {
        if (!(authClaim instanceof Number auth)) {
            throw new BadJwtException("Incomplete authenticated claims");
        }
        try {
            if (new java.math.BigDecimal(auth.toString()).longValueExact() < 0) {
                throw new ArithmeticException("negative auth_time");
            }
        } catch (ArithmeticException invalid) {
            throw new BadJwtException("Invalid authenticated claims");
        }
    }

    private static OAuth2TokenValidatorResult validateJwtClaims(
            Jwt jwt, String expectedAudience) {
        if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        }
        if (jwt.getAudience() == null || !jwt.getAudience().contains(expectedAudience)) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        }
        if (jwt.getIssuedAt() == null || jwt.getExpiresAt() == null) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        }
        try {
            principal(jwt);
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException invalid) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        }
    }

    @Bean
    SecurityFilterChain readSecurity(HttpSecurity http, JwtDecoder readJwtDecoder)
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
                                o.jwt(j -> j.decoder(readJwtDecoder))
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
    PrincipalResolver principalResolver() {
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
        Instant auth = extractAuthTime(jwt);
        if (jwt.getIssuer() == null || auth == null) {
            throw new IllegalArgumentException("incomplete authenticated claims");
        }
        if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new IllegalArgumentException("incomplete authenticated claims");
        }
        requireValidTemporalBounds(jwt, auth);
        return new AuthenticatedExternalPrincipal(
                jwt.getIssuer().toString(),
                jwt.getSubject(),
                auth,
                jwt.getIssuedAt(),
                jwt.getExpiresAt(),
                Set.of());
    }

    private static void requireValidTemporalBounds(Jwt jwt, Instant auth) {
        if (jwt.getIssuedAt() == null || jwt.getExpiresAt() == null) {
            throw new IllegalArgumentException("incomplete authenticated claims");
        }
        if (auth.isAfter(jwt.getIssuedAt()) || !jwt.getIssuedAt().isBefore(jwt.getExpiresAt())) {
            throw new IllegalArgumentException("incomplete authenticated claims");
        }
    }

    private static Instant extractAuthTime(Jwt jwt) {
        Object claim = jwt.getClaim("auth_time");
        if (claim instanceof Instant i) return i;
        if (claim instanceof Number n) return Instant.ofEpochSecond(n.longValue());
        return null;
    }
}
