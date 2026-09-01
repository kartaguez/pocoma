package com.kartaguez.pocoma.engine.taskexecution.model;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/** Functional reference to an artifact durably produced or adopted by a Task. */
public record ProducedArtifactReference(
		String namespace,
		String type,
		String id,
		OptionalLong version,
		Optional<BusinessObjectVersion> subject,
		Instant createdAt) {
	public ProducedArtifactReference {
		namespace = requireText(namespace, "namespace");
		type = requireText(type, "type");
		id = requireText(id, "id");
		version = requireNonNull(version, "version must not be null");
		if (version.isPresent() && version.getAsLong() < 1) {
			throw new IllegalArgumentException("version must be greater than or equal to 1");
		}
		subject = requireNonNull(subject, "subject must not be null");
		requireNonNull(createdAt, "createdAt must not be null");
	}

	private static String requireText(String value, String name) {
		requireNonNull(value, name + " must not be null");
		if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}
}
