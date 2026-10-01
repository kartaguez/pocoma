package com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class JpaConsumptionSlotRepositoryFencingQueryTest {

	@Test
	void finalCasDependsOnlyOnPendingStatusAndCurrentClaim() throws Exception {
		Query query = JpaConsumptionSlotRepository.class
				.getMethod("terminalize", UUID.class, UUID.class, String.class, String.class, Instant.class)
				.getAnnotation(Query.class);
		String sql = query.value().toLowerCase().replaceAll("\\s+", " ");

		assertTrue(sql.contains("status = 'pending'"));
		assertTrue(sql.contains("current_claim_id = :claimid"));
		assertFalse(sql.contains("lease_until"));
		assertFalse(sql.contains("now()"));
	}
}
