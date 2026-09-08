package com.kartaguez.pocoma.engine.read.projection;

import java.time.Clock;
import java.util.UUID;
import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinitionRegistry;
import com.kartaguez.pocoma.domain.projection.*;

public final class ProjectionMaterializationService<A> {
	private final ProjectionMetadataPort metadata;
	private final ReadStoreTransactionRunner transactions;
	private final ProjectionArtifactWriter<A> writer;
	private final Clock clock;
	private final PipelineDefinitionRegistry definitions;

	public ProjectionMaterializationService(ProjectionMetadataPort metadata, ReadStoreTransactionRunner transactions,
			ProjectionArtifactWriter<A> writer, Clock clock, PipelineDefinitionRegistry definitions) {
		this.metadata = requireNonNull(metadata); this.transactions = requireNonNull(transactions);
		this.writer = requireNonNull(writer); this.clock = requireNonNull(clock);
		this.definitions = requireNonNull(definitions);
	}

	public ProjectionMaterializationResult materialize(ProjectionIdentity identity, A artifact) {
		return transactions.run(() -> materializeLocked(identity, artifact));
	}

	private ProjectionMaterializationResult materializeLocked(ProjectionIdentity identity, A artifact) {
		metadata.lock(identity);
		var definition = definitions.require(identity.generation().pipeline());
		if (!definition.appliesTo(identity.potVersion())) return new ProjectionMaterializationResult.NotApplicable();
		var existing = metadata.findArtifact(identity);
		var failure = metadata.findFailure(identity);
		if (existing.isPresent() && failure.isPresent()) throw new IllegalStateException("Projection cannot have both an artifact and a failure");
		if (existing.isPresent()) {
			if (writer.hasSameContent(existing.get(), artifact)) return new ProjectionMaterializationResult.AlreadySatisfied(existing.get());
			var violation = new ProjectionInvariantViolation(UUID.randomUUID(), identity, existing.get().artifactId(),
					existing.get().digest(), writer.digest(artifact), clock.instant());
			metadata.recordViolation(violation);
			return new ProjectionMaterializationResult.DivergentDuplicate(violation);
		}
		if (failure.isPresent()) return new ProjectionMaterializationResult.AlreadyFailed(failure.get());
		var descriptor = new ProjectionArtifactDescriptor(ProjectionArtifactId.random(), identity,
				writer.digest(artifact), clock.instant());
		writer.write(descriptor.artifactId(), identity, artifact);
		metadata.insertArtifact(descriptor);
		metadata.advanceHead(identity.generation(), identity.potVersion(), descriptor.createdAt());
		return new ProjectionMaterializationResult.Created(descriptor);
	}
}
