package com.kartaguez.pocoma.engine.read.projection;

import java.time.Clock;
import java.util.UUID;

import com.kartaguez.pocoma.domain.projection.*;

public final class ProjectionMaterializationService<A> {
	private final ProjectionMetadataPort metadata;
	private final ReadStoreTransactionRunner transactions;
	private final ProjectionArtifactWriter<A> writer;
	private final Clock clock;

	public ProjectionMaterializationService(ProjectionMetadataPort metadata, ReadStoreTransactionRunner transactions,
			ProjectionArtifactWriter<A> writer, Clock clock) {
		this.metadata = metadata; this.transactions = transactions; this.writer = writer; this.clock = clock;
	}

	public ProjectionMaterializationResult materialize(ProjectionIdentity identity, A artifact) {
		return transactions.run(() -> materializeLocked(identity, artifact));
	}

	private ProjectionMaterializationResult materializeLocked(ProjectionIdentity identity, A artifact) {
		metadata.lock(identity);
		var coverage = metadata.findCoverage(identity.generation());
		if (coverage.isEmpty() || !coverage.get().contains(identity.potVersion())) return new ProjectionMaterializationResult.NotExpected();
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
