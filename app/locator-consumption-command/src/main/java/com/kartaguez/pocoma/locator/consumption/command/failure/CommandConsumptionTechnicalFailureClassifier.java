package com.kartaguez.pocoma.locator.consumption.command.failure;

import static java.util.Objects.requireNonNull;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLTransientException;
import java.time.Clock;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;
import com.kartaguez.pocoma.engine.command.decode.InvalidCommandPayloadException;
import com.kartaguez.pocoma.engine.command.decode.UnknownCommandTypeException;
import com.kartaguez.pocoma.engine.command.dispatch.MissingCommandUseCaseException;
import com.kartaguez.pocoma.engine.command.execution.CommandExecutionInvariantViolationException;
import com.kartaguez.pocoma.engine.command.execution.RecordedCommandNotFoundException;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionTechnicalFailureClassifier;

/** Conservative Command classifier: only recognized transient infrastructure failures retry. */
public final class CommandConsumptionTechnicalFailureClassifier implements ConsumptionTechnicalFailureClassifier {

	private static final int MAX_MESSAGE_LENGTH = 1_000;
	private final Clock clock;

	public CommandConsumptionTechnicalFailureClassifier(Clock clock) {
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	public ProcessingFailure classify(RuntimeException failure) {
		requireNonNull(failure, "failure must not be null");
		if (failure instanceof LostClaimException) {
			throw new IllegalArgumentException("LostClaimException must never be classified", failure);
		}

		Classification classification = classificationFor(failure);
		return new ProcessingFailure(
				new ProcessingFailureCode(classification.code()),
				classification.category().name(),
				messageFor(failure),
				clock.instant());
	}

	private static Classification classificationFor(RuntimeException failure) {
		if (failure instanceof RecordedCommandNotFoundException) {
			return terminal("COMMAND_NOT_FOUND", CommandConsumptionFailureCategory.COMMAND_INPUT_NOT_FOUND);
		}
		if (failure instanceof UnknownCommandTypeException) {
			return terminal("UNSUPPORTED_COMMAND_TYPE", CommandConsumptionFailureCategory.COMMAND_CONFIGURATION);
		}
		if (failure instanceof InvalidCommandPayloadException) {
			return terminal("INVALID_COMMAND_PAYLOAD", CommandConsumptionFailureCategory.COMMAND_CONFIGURATION);
		}
		if (failure instanceof MissingCommandUseCaseException) {
			return terminal("MISSING_COMMAND_USE_CASE", CommandConsumptionFailureCategory.COMMAND_CONFIGURATION);
		}
		if (failure instanceof CommandExecutionInvariantViolationException) {
			return terminal("TECHNICAL_INVARIANT_VIOLATION",
					CommandConsumptionFailureCategory.COMMAND_TECHNICAL_INVARIANT);
		}

		SQLException sql = sqlCause(failure);
		if (sql != null) return classifySql(sql);
		return terminal("COMMAND_EXECUTION_FAILURE", CommandConsumptionFailureCategory.COMMAND_EXECUTION_FAILURE);
	}

	private static Classification classifySql(SQLException failure) {
		String state = failure.getSQLState();
		if ("40P01".equals(state)) return transientFailure("DEADLOCK");
		if ("40001".equals(state)) return transientFailure("SERIALIZATION_FAILURE");
		if (failure instanceof SQLTimeoutException || "57014".equals(state)) return transientFailure("TIMEOUT");
		if (failure instanceof SQLTransientConnectionException
				|| failure instanceof SQLRecoverableException
				|| state != null && state.startsWith("08")) {
			return transientFailure("DATABASE_UNAVAILABLE");
		}
		if (failure instanceof SQLTransientException) return transientFailure("TRANSIENT_INFRASTRUCTURE_ERROR");
		return terminal("COMMAND_EXECUTION_FAILURE", CommandConsumptionFailureCategory.COMMAND_EXECUTION_FAILURE);
	}

	private static SQLException sqlCause(Throwable failure) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current instanceof SQLException sql) return sql;
			if (current.getCause() == current) break;
		}
		return null;
	}

	private static Classification transientFailure(String code) {
		return new Classification(code, CommandConsumptionFailureCategory.TRANSIENT);
	}

	private static Classification terminal(String code, CommandConsumptionFailureCategory category) {
		return new Classification(code, category);
	}

	private static String messageFor(RuntimeException failure) {
		String message = failure.getMessage();
		if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
		message = message.replace('\r', ' ').replace('\n', ' ');
		return message.length() <= MAX_MESSAGE_LENGTH ? message : message.substring(0, MAX_MESSAGE_LENGTH);
	}

	private record Classification(String code, CommandConsumptionFailureCategory category) {
	}
}
