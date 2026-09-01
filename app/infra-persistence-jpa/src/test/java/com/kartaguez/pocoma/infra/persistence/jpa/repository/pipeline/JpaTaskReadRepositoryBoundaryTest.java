package com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class JpaTaskReadRepositoryBoundaryTest {
	@Test
	void candidateQueryKnowsNeitherConsumptionNorLegacyLifecycle() {
		String sql = JpaTaskReadRepository.SELECT_CANDIDATES.toLowerCase();
		for (String forbidden : new String[] { "consumption_slots", "status", "claim_token", "lease_until",
				"attempt_count", "current_claim_id" }) {
			assertFalse(sql.contains(forbidden), () -> "Task discovery must not contain " + forbidden);
		}
	}
}
