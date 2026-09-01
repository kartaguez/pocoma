package com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaTaskConsumptionDiscoveryRepository;

class JpaTaskReadRepositoryBoundaryTest {
	@Test
	void structuralReadKnowsNeitherConsumptionNorLegacyLifecycle() {
		String sql = JpaTaskReadRepository.SELECT_BY_ID.toLowerCase();
		for (String forbidden : new String[] { "consumption_slots", "status", "claim_token", "lease_until",
				"attempt_count", "current_claim_id" }) {
			assertFalse(sql.contains(forbidden), () -> "Task structural read must not contain " + forbidden);
		}
	}

	@Test
	void discoveryNeverReadsLegacyTaskLifecycle() {
		String sql = JpaTaskConsumptionDiscoveryRepository.SELECT_ELIGIBLE.toLowerCase();
		for (String forbidden : new String[] { "task.status", "task.claim_token", "task.lease_until",
				"task.attempt_count", "task.current_claim_id" }) {
			assertFalse(sql.contains(forbidden), () -> "Task discovery must not contain " + forbidden);
		}
	}
}
