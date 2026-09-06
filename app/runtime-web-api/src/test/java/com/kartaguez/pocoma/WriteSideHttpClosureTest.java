package com.kartaguez.pocoma;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.supra.http.rest.spring.filter.CommandRequestSizeFilter;

@SpringBootTest(properties = {
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"pocoma.query.balance.pipeline-version=2",
		"spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.test",
		"spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.test/jwks"
})
class WriteSideHttpClosureTest {

	private static final Set<String> LEGACY_MUTATION_PATHS = Set.of(
			"/api/pots",
			"/api/pots/{potId}/details",
			"/api/pots/{potId}",
			"/api/pots/{potId}/shareholders",
			"/api/pots/{potId}/shareholders/details",
			"/api/pots/{potId}/shareholders/weights",
			"/api/pots/{potId}/expenses",
			"/api/expenses/{expenseId}/details",
			"/api/expenses/{expenseId}/shares",
			"/api/expenses/{expenseId}");

	@Autowired WebApplicationContext context;
	@Autowired CommandRequestSizeFilter commandRequestSizeFilter;
	@Autowired ObjectMapper mapper;
	private MockMvc http;

	@BeforeEach
	void setUpHttp() {
		http = MockMvcBuilders.webAppContextSetup(context).addFilters(commandRequestSizeFilter)
				.apply(springSecurity()).build();
	}

	@Test
	void openApiExposesAsyncAdmissionAndNoLegacyMutationOperation() throws Exception {
		String document = http.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		var paths = mapper.readTree(document).path("paths");

		assertTrue(paths.path("/api/v1/commands").has("post"));
		for (String legacyPath : LEGACY_MUTATION_PATHS) {
			if (paths.has(legacyPath)) {
				assertFalse(paths.path(legacyPath).has("post"));
				assertFalse(paths.path(legacyPath).has("patch"));
				assertFalse(paths.path(legacyPath).has("delete"));
			}
		}
	}
}
