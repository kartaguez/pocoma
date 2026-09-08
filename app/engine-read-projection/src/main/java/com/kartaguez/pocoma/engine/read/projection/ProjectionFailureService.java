package com.kartaguez.pocoma.engine.read.projection;

import java.time.Instant;
import com.kartaguez.pocoma.domain.projection.*;

public final class ProjectionFailureService {
	private final ProjectionMetadataPort metadata;
	private final ReadStoreTransactionRunner transactions;
	public ProjectionFailureService(ProjectionMetadataPort metadata, ReadStoreTransactionRunner transactions) {
		this.metadata = metadata; this.transactions = transactions;
	}
	public ProjectionFailureResult record(ProjectionIdentity identity, Instant failedAt, String failureCode) {
		return transactions.run(() -> {
			metadata.lock(identity);
			var coverage = metadata.findCoverage(identity.generation());
			if (coverage.isEmpty() || !coverage.get().contains(identity.potVersion())) return new ProjectionFailureResult.NotExpected();
			var artifact = metadata.findArtifact(identity);
			var failure = metadata.findFailure(identity);
			if (artifact.isPresent() && failure.isPresent()) throw new IllegalStateException("Projection cannot have both an artifact and a failure");
			if (artifact.isPresent()) return new ProjectionFailureResult.AlreadyReady(artifact.get());
			if (failure.isPresent()) return new ProjectionFailureResult.AlreadyFailed(failure.get());
			var created = new ProjectionFailure(identity, failedAt, failureCode);
			metadata.insertFailure(created);
			return new ProjectionFailureResult.Recorded(created);
		});
	}
}
