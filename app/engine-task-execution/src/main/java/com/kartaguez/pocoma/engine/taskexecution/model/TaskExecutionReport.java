package com.kartaguez.pocoma.engine.taskexecution.model;

import static java.util.Objects.requireNonNull;

import java.util.List;

/** Functional outcome of a typed Task execution, independent from consumption provenance. */
public sealed interface TaskExecutionReport {
	List<BusinessObjectVersion> inputs();
	List<ProducedArtifactReference> artifacts();

	record Succeeded(List<BusinessObjectVersion> inputs, List<ProducedArtifactReference> artifacts)
			implements TaskExecutionReport {
		public Succeeded {
			inputs = List.copyOf(requireNonNull(inputs, "inputs must not be null"));
			artifacts = List.copyOf(requireNonNull(artifacts, "artifacts must not be null"));
		}
	}

	record Rejected(String rejectionCode, List<BusinessObjectVersion> inputs,
			List<ProducedArtifactReference> artifacts) implements TaskExecutionReport {
		public Rejected {
			requireNonNull(rejectionCode, "rejectionCode must not be null");
			if (rejectionCode.isBlank()) throw new IllegalArgumentException("rejectionCode must not be blank");
			inputs = List.copyOf(requireNonNull(inputs, "inputs must not be null"));
			artifacts = List.copyOf(requireNonNull(artifacts, "artifacts must not be null"));
		}
	}
}
