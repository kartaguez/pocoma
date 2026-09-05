package com.kartaguez.pocoma.locator.consumption.command.failure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.engine.command.decode.InvalidCommandPayloadException;
import com.kartaguez.pocoma.engine.command.decode.UnknownCommandTypeException;
import com.kartaguez.pocoma.engine.command.execution.CommandExecutionInvariantViolationException;
import com.kartaguez.pocoma.engine.command.execution.RecordedCommandNotFoundException;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.model.CommandType;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;

class CommandConsumptionTechnicalFailureClassifierTest {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
	private final CommandConsumptionTechnicalFailureClassifier classifier =
			new CommandConsumptionTechnicalFailureClassifier(Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void classifiesDurableInputConfigurationAndInvariantFailuresAsTerminalCategories() {
		assertFailure(new RecordedCommandNotFoundException(new CommandId(UUID.randomUUID())),
				"COMMAND_NOT_FOUND", "COMMAND_INPUT_NOT_FOUND");
		assertFailure(new UnknownCommandTypeException(new CommandType("UNKNOWN_V1")),
				"UNSUPPORTED_COMMAND_TYPE", "COMMAND_CONFIGURATION");
		assertFailure(new InvalidCommandPayloadException(new CommandType("TEST_V1"), "bad"),
				"INVALID_COMMAND_PAYLOAD", "COMMAND_CONFIGURATION");
		assertFailure(new CommandExecutionInvariantViolationException("inconsistent"),
				"TECHNICAL_INVARIANT_VIOLATION", "COMMAND_TECHNICAL_INVARIANT");
	}

	@Test
	void retriesOnlyExplicitlyRecognizedSqlFailuresEvenWhenNested() {
		assertFailure(nested(new SQLException("deadlock", "40P01")), "DEADLOCK", "TRANSIENT");
		assertFailure(nested(new SQLException("serialization", "40001")), "SERIALIZATION_FAILURE", "TRANSIENT");
		assertFailure(nested(new SQLException("connection", "08006")), "DATABASE_UNAVAILABLE", "TRANSIENT");
		assertFailure(nested(new SQLTimeoutException("timeout")), "TIMEOUT", "TRANSIENT");
		assertFailure(nested(new SQLTransientException("temporary", "HY000")),
				"TRANSIENT_INFRASTRUCTURE_ERROR", "TRANSIENT");
	}

	@Test
	void unexpectedRuntimeAndNonTransientSqlFailuresAreTerminalByDefault() {
		assertFailure(new NullPointerException("bug"), "COMMAND_EXECUTION_FAILURE", "COMMAND_EXECUTION_FAILURE");
		assertFailure(new IllegalStateException("bug"), "COMMAND_EXECUTION_FAILURE", "COMMAND_EXECUTION_FAILURE");
		assertFailure(nested(new SQLException("constraint", "23505")),
				"COMMAND_EXECUTION_FAILURE", "COMMAND_EXECUTION_FAILURE");
	}

	@Test
	void lostClaimIsNeverClassified() {
		assertThrows(IllegalArgumentException.class, () -> classifier.classify(
				new LostClaimException(UUID.randomUUID(), ClaimId.generate())));
	}

	private void assertFailure(RuntimeException exception, String code, String category) {
		var failure = classifier.classify(exception);
		assertEquals(code, failure.code().value());
		assertEquals(category, failure.category());
		assertEquals(NOW, failure.occurredAt());
	}

	private static RuntimeException nested(SQLException exception) {
		return new RuntimeException("outer", new IllegalStateException("middle", exception));
	}
}
