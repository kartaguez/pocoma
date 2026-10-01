package com.kartaguez.pocoma.domain.projection;

@FunctionalInterface
public interface JsonSchemaValidator {
	boolean isValid(JsonValue schema, JsonValue payload);
}
