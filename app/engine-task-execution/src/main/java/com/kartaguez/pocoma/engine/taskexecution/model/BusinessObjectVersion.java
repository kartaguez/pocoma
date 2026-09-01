package com.kartaguez.pocoma.engine.taskexecution.model;

import static java.util.Objects.requireNonNull;

/** Functional reference to a business object version actually used by a Task. */
public record BusinessObjectVersion(String type, String id, long version) {
	public BusinessObjectVersion {
		type = requireText(type, "type");
		id = requireText(id, "id");
		if (version < 1) throw new IllegalArgumentException("version must be greater than or equal to 1");
	}

	private static String requireText(String value, String name) {
		requireNonNull(value, name + " must not be null");
		if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}
}
