package com.kartaguez.pocoma.engine.read.projection;

import java.time.Instant;
import static java.util.Objects.requireNonNull;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinitionRegistry;
import com.kartaguez.pocoma.domain.projection.*;

public final class ProjectionFailureService {
	private final ProjectionMetadataPort metadata;
	private final ReadStoreTransactionRunner transactions;
	private final PipelineDefinitionRegistry definitions;
	public ProjectionFailureService(ProjectionMetadataPort metadata, ReadStoreTransactionRunner transactions,
			PipelineDefinitionRegistry definitions) {
		this.metadata = requireNonNull(metadata); this.transactions = requireNonNull(transactions);
		this.definitions = requireNonNull(definitions);
	}
	public ProjectionFailureResult record(ProjectionIdentity identity, Instant failedAt, String failureCode) {
		return transactions.run(() -> {
			metadata.lock(identity);
			var definition = definitions.require(identity.generation().pipeline());
			if (!definition.appliesTo(identity.potVersion())) return new ProjectionFailureResult.NotApplicable();
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
