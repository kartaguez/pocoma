package com.kartaguez.pocoma.supra.authentication.springsecurity;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import com.kartaguez.pocoma.orchestrator.command.admission.model.AuthenticatedExternalPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(classes = CommandAdmissionResourceServerTest.TestApplication.class, properties = {
		"pocoma.command-admission.enabled=true"
})
class CommandAdmissionResourceServerTest {
	private static final String ISSUER = "https://issuer.example";
	private static final String AUDIENCE = "pocoma-api";
	private static final KeyPair TRUSTED_KEYS = keys();
	private static final KeyPair UNTRUSTED_KEYS = keys();

	@Autowired private WebApplicationContext context;
	@Autowired private JwtEncoder trustedEncoder;
	private MockMvc mvc;

	@BeforeEach
	void configureMvc() {
		mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
				.apply(springSecurity()).build();
	}

	@Test
	void acceptsAValidTokenThroughTheStandardResourceServer() throws Exception {
		mvc.perform(post("/api/v1/commands").header("Authorization", "Bearer " + token(
				trustedEncoder, ISSUER, List.of(AUDIENCE), Instant.now().minusSeconds(10),
				Instant.now().plusSeconds(300), Instant.now().minusSeconds(10))))
				.andExpect(status().isOk());
	}

	@Test
	void rejectsMissingAndInvalidSignatures() throws Exception {
		mvc.perform(post("/api/v1/commands")).andExpect(status().isUnauthorized());
		JwtEncoder untrusted = encoder(UNTRUSTED_KEYS);
		mvc.perform(post("/api/v1/commands").header("Authorization", "Bearer " + token(
				untrusted, ISSUER, List.of(AUDIENCE), Instant.now().minusSeconds(10),
				Instant.now().plusSeconds(300), Instant.now().minusSeconds(10))))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsIssuerAndAudienceMismatches() throws Exception {
		mvc.perform(post("/api/v1/commands").header("Authorization", "Bearer " + token(
				trustedEncoder, "https://other.example", List.of(AUDIENCE), Instant.now().minusSeconds(10),
				Instant.now().plusSeconds(300), Instant.now().minusSeconds(10))))
				.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/commands").header("Authorization", "Bearer " + token(
				trustedEncoder, ISSUER, List.of("other-api"), Instant.now().minusSeconds(10),
				Instant.now().plusSeconds(300), Instant.now().minusSeconds(10))))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsExpiredAndNotYetValidTokens() throws Exception {
		mvc.perform(post("/api/v1/commands").header("Authorization", "Bearer " + token(
				trustedEncoder, ISSUER, List.of(AUDIENCE), Instant.now().minusSeconds(300),
				Instant.now().minusSeconds(120), Instant.now().minusSeconds(300))))
				.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/commands").header("Authorization", "Bearer " + token(
				trustedEncoder, ISSUER, List.of(AUDIENCE), Instant.now().minusSeconds(10),
				Instant.now().plusSeconds(600), Instant.now().plusSeconds(300))))
				.andExpect(status().isUnauthorized());
	}

	private static String token(JwtEncoder encoder, String issuer, List<String> audiences,
			Instant issuedAt, Instant expiresAt, Instant notBefore) {
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(issuer).subject("external-subject").audience(audiences)
				.issuedAt(issuedAt).expiresAt(expiresAt).notBefore(notBefore)
				.claim("auth_time", issuedAt.getEpochSecond())
				.claim("scope", "pocoma:pot:create ignored:authority")
				.build();
		return encoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
	}

	private static JwtEncoder encoder(KeyPair pair) {
		return NimbusJwtEncoder.withKeyPair(
				(RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate()).build();
	}

	private static KeyPair keys() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		}
		catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import({CommandAdmissionSecurityConfiguration.class, ProbeController.class})
	static class TestApplication {
		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}

		@Bean
		JwtEncoder jwtEncoder() {
			return encoder(TRUSTED_KEYS);
		}

		@Bean
		JwtDecoder jwtDecoder() {
			NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) TRUSTED_KEYS.getPublic()).build();
			decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
					JwtValidators.createDefaultWithIssuer(ISSUER), new JwtAudienceValidator(AUDIENCE)));
			return decoder;
		}
	}

	@RestController
	static class ProbeController {
		@PostMapping("/api/v1/commands")
		void submit(AuthenticatedExternalPrincipal principal) {
		}
	}
}
