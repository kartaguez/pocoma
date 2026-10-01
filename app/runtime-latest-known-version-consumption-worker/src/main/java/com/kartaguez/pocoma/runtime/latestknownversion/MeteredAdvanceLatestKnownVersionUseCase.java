package com.kartaguez.pocoma.runtime.latestknownversion;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.kartaguez.pocoma.engine.read.projection.AdvanceLatestKnownVersionInput;
import com.kartaguez.pocoma.engine.read.projection.AdvanceLatestKnownVersionUseCase;
import com.kartaguez.pocoma.engine.read.projection.LatestKnownVersionUpdate;

final class MeteredAdvanceLatestKnownVersionUseCase implements AdvanceLatestKnownVersionUseCase {
	private static final Logger LOGGER = LoggerFactory.getLogger(MeteredAdvanceLatestKnownVersionUseCase.class);
	private final AdvanceLatestKnownVersionUseCase delegate;
	private final Counter advanced;
	private final Counter unchanged;
	private final Counter errors;

	MeteredAdvanceLatestKnownVersionUseCase(AdvanceLatestKnownVersionUseCase delegate, MeterRegistry registry) {
		this.delegate = delegate;
		this.advanced = counter(registry, "advanced");
		this.unchanged = counter(registry, "unchanged");
		this.errors = counter(registry, "error");
	}

	@Override
	public LatestKnownVersionUpdate advanceToAtLeast(AdvanceLatestKnownVersionInput input) {
		try {
			LatestKnownVersionUpdate result = delegate.advanceToAtLeast(input);
			String outcome;
			if (result instanceof LatestKnownVersionUpdate.Advanced) outcome = "advanced";
			else {
				outcome = "unchanged";
			}
			afterCommit(() -> {
				if (result instanceof LatestKnownVersionUpdate.Advanced) advanced.increment();
				else unchanged.increment();
				LOGGER.info("latest-known-version update potId={} candidateVersion={} resultingLatestKnownVersion={} outcome={}",
						input.potId().value(), input.candidateVersion(),
						result.latestKnownVersion().latestKnownVersion(), outcome);
			});
			return result;
		}
		catch (RuntimeException failure) {
			errors.increment();
			LOGGER.warn("latest-known-version update failed potId={} candidateVersion={} outcome=error",
					input.potId().value(), input.candidateVersion(), failure);
			throw failure;
		}
	}

	private static Counter counter(MeterRegistry registry, String outcome) {
		return Counter.builder("pocoma.latest.known.version.updates")
				.tag("outcome", outcome).register(registry);
	}

	private static void afterCommit(Runnable action) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			throw new IllegalStateException("latest-known-version updates require transaction synchronization");
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				action.run();
			}
		});
	}
}
