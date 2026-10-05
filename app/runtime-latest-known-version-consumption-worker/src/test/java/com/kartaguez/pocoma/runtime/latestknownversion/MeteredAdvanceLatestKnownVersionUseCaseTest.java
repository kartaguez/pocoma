package com.kartaguez.pocoma.runtime.latestknownversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.materialize.latestknownversion.AdvanceLatestKnownVersionInput;
import com.kartaguez.pocoma.engine.materialize.latestknownversion.LatestKnownVersion;
import com.kartaguez.pocoma.engine.materialize.latestknownversion.LatestKnownVersionUpdate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MeteredAdvanceLatestKnownVersionUseCaseTest {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	@AfterEach
	void clearSynchronization() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
		registry.close();
	}

	@Test
	void publishesAdvancedAndUnchangedOnlyAfterCommit() {
		PotId potId = PotId.of(UUID.randomUUID());
		var value = new LatestKnownVersion(potId, 8);
		var input = new AdvanceLatestKnownVersionInput(potId, 8, Instant.parse("2026-01-01T00:00:00Z"));
		var advanced = new MeteredAdvanceLatestKnownVersionUseCase(
				ignored -> new LatestKnownVersionUpdate.Advanced(value), registry);

		TransactionSynchronizationManager.initSynchronization();
		advanced.advanceToAtLeast(input);
		assertEquals(0.0, count("advanced"));
		TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCommit());
		TransactionSynchronizationManager.clearSynchronization();
		assertEquals(1.0, count("advanced"));

		var unchanged = new MeteredAdvanceLatestKnownVersionUseCase(
				ignored -> new LatestKnownVersionUpdate.Unchanged(value), registry);
		TransactionSynchronizationManager.initSynchronization();
		unchanged.advanceToAtLeast(input);
		TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCommit());
		TransactionSynchronizationManager.clearSynchronization();
		assertEquals(1.0, count("unchanged"));
	}

	@Test
	void publishesErrorsAndRethrowsTheFailure() {
		PotId potId = PotId.of(UUID.randomUUID());
		var input = new AdvanceLatestKnownVersionInput(potId, 8, Instant.parse("2026-01-01T00:00:00Z"));
		var metered = new MeteredAdvanceLatestKnownVersionUseCase(ignored -> {
			throw new ExpectedFailure();
		}, registry);

		assertThrows(ExpectedFailure.class, () -> metered.advanceToAtLeast(input));

		assertEquals(1.0, count("error"));
	}

	private double count(String outcome) {
		return registry.get("pocoma.latest.known.version.updates")
				.tag("outcome", outcome).counter().count();
	}

	private static final class ExpectedFailure extends RuntimeException {}
}
