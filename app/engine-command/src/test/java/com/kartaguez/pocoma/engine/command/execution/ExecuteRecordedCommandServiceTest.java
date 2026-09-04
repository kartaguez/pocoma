package com.kartaguez.pocoma.engine.command.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;
import com.kartaguez.pocoma.engine.command.decode.CommandDecoder;
import com.kartaguez.pocoma.engine.command.decode.CommandDecoderRegistry;
import com.kartaguez.pocoma.engine.command.decode.CommandPayloadDecoder;
import com.kartaguez.pocoma.engine.command.decode.InvalidCommandPayloadException;
import com.kartaguez.pocoma.engine.command.decode.UnknownCommandTypeException;
import com.kartaguez.pocoma.engine.command.dispatch.CommandDispatcher;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCase;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.command.dispatch.MissingCommandUseCaseException;
import com.kartaguez.pocoma.engine.command.model.AuthorizationSnapshot;
import com.kartaguez.pocoma.engine.command.model.Command;
import com.kartaguez.pocoma.engine.command.model.CommandExecutionArtifact;
import com.kartaguez.pocoma.engine.command.model.CommandExecutionInput;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.model.CommandProducedEvent;
import com.kartaguez.pocoma.engine.command.model.CommandType;
import com.kartaguez.pocoma.engine.command.model.PocomaUserId;
import com.kartaguez.pocoma.engine.command.model.RecordedCommand;
import com.kartaguez.pocoma.engine.command.port.out.EventAppendPort;

class ExecuteRecordedCommandServiceTest {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final CommandId COMMAND_ID = new CommandId(UUID.randomUUID());
	private static final CommandType COMMAND_TYPE = new CommandType("TEST_COMMAND_V1");

	@Test
	void missingRecordedCommandIsATechnicalFailure() {
		Fixture fixture = new Fixture(Optional.empty(), success(List.of(), List.of()));

		assertThrows(RecordedCommandNotFoundException.class, () -> fixture.service.execute(COMMAND_ID));
		assertEquals(0, fixture.decodeCalls.get());
	}

	@Test
	void expiredAuthorizationRejectsBeforeDecodeDispatchAndAppend() {
		Fixture beforeExpiry = new Fixture(Optional.of(recorded(NOW.minusNanos(1))), success(List.of(), List.of()));
		Fixture atExpiry = new Fixture(Optional.of(recorded(NOW)), success(List.of(), List.of()));

		assertExpired(beforeExpiry);
		assertExpired(atExpiry);
	}

	@Test
	void invalidPayloadUnknownTypeAndUnknownUseCaseRemainTechnicalFailures() {
		RecordedCommand recorded = recorded(NOW.plusSeconds(60));
		CommandPayloadDecoder<TestCommand> invalid = decoder(payload -> { throw new IllegalArgumentException("bad"); });
		ExecuteRecordedCommandService invalidPayload = service(recorded,
				new CommandDecoderRegistry(List.of(invalid)), dispatcher(success(List.of(), List.of())), events(List.of()));
		assertThrows(InvalidCommandPayloadException.class, () -> invalidPayload.execute(COMMAND_ID));

		ExecuteRecordedCommandService unknownType = service(recorded,
				new CommandDecoderRegistry(List.of()), dispatcher(success(List.of(), List.of())), events(List.of()));
		assertThrows(UnknownCommandTypeException.class, () -> unknownType.execute(COMMAND_ID));

		ExecuteRecordedCommandService unknownUseCase = service(recorded,
				new CommandDecoderRegistry(List.of(decoder(TestCommand::new))), new CommandDispatcher(List.of()),
				events(List.of()));
		assertThrows(MissingCommandUseCaseException.class, () -> unknownUseCase.execute(COMMAND_ID));
	}

	@Test
	void successWithoutEventsDoesNotCallAppend() {
		CommandExecutionInput input = new CommandExecutionInput("POT", "pot-1", 3);
		Fixture fixture = new Fixture(Optional.of(recorded(NOW.plusSeconds(60))), success(List.of(input), List.of()));

		RecordedCommandExecutionResult.Succeeded result = assertInstanceOf(
				RecordedCommandExecutionResult.Succeeded.class, fixture.service.execute(COMMAND_ID));

		assertEquals(List.of(input), result.inputs());
		assertEquals(List.of(), result.artifacts());
		assertEquals(0, fixture.appendCalls.get());
	}

	@Test
	void successAppendsAllEventsOnceAndReturnsTheirArtifacts() {
		CommandExecutionInput subject = new CommandExecutionInput("POT", "pot-1", 4);
		List<CommandProducedEvent> produced = List.of(
				new CommandProducedEvent("POT_UPDATED_V1", "{\"n\":1}", Optional.of(subject)),
				new CommandProducedEvent("AUDIT_RECORDED_V1", "{\"n\":2}", Optional.empty()));
		List<CommandExecutionArtifact> artifacts = List.of(
				artifact("event-1", produced.get(0)), artifact("event-2", produced.get(1)));
		Fixture fixture = new Fixture(Optional.of(recorded(NOW.plusSeconds(60))),
				success(List.of(subject), produced), artifacts);

		RecordedCommandExecutionResult.Succeeded result = assertInstanceOf(
				RecordedCommandExecutionResult.Succeeded.class, fixture.service.execute(COMMAND_ID));

		assertEquals(1, fixture.appendCalls.get());
		assertEquals(produced, fixture.appendedEvents);
		assertEquals(artifacts, result.artifacts());
	}

	@Test
	void businessRejectionPreservesReasonAndNeverAppendsEvents() {
		TerminalReason reason = new TerminalReason("BUSINESS_CONFLICT");
		CommandExecutionInput input = new CommandExecutionInput("POT", "pot-1", 4);
		Fixture fixture = new Fixture(Optional.of(recorded(NOW.plusSeconds(60))),
				new CommandUseCaseResult.Rejected(reason, List.of(input)));

		RecordedCommandExecutionResult.Rejected result = assertInstanceOf(
				RecordedCommandExecutionResult.Rejected.class, fixture.service.execute(COMMAND_ID));

		assertEquals(reason, result.reason());
		assertEquals(List.of(input), result.inputs());
		assertEquals(0, fixture.appendCalls.get());
	}

	@Test
	void appendFailureAndInvalidAppendReportsRemainTechnical() {
		CommandProducedEvent produced = new CommandProducedEvent("EVENT_V1", "{}", Optional.empty());
		TechnicalFailure expected = new TechnicalFailure();
		ExecuteRecordedCommandService failing = service(recorded(NOW.plusSeconds(60)),
				decoderRegistry(), dispatcher(success(List.of(), List.of(produced))), eventsThrowing(expected));
		assertSame(expected, assertThrows(TechnicalFailure.class, () -> failing.execute(COMMAND_ID)));

		ExecuteRecordedCommandService nullReport = service(recorded(NOW.plusSeconds(60)),
				decoderRegistry(), dispatcher(success(List.of(), List.of(produced))), ignored -> null);
		assertThrows(NullPointerException.class, () -> nullReport.execute(COMMAND_ID));

		ExecuteRecordedCommandService wrongCount = service(recorded(NOW.plusSeconds(60)),
				decoderRegistry(), dispatcher(success(List.of(), List.of(produced))), events(List.of()));
		assertThrows(IllegalStateException.class, () -> wrongCount.execute(COMMAND_ID));

		CommandProducedEvent other = new CommandProducedEvent("OTHER_EVENT_V1", "{}", Optional.empty());
		List<CommandProducedEvent> producedInOrder = List.of(produced, other);
		List<CommandExecutionArtifact> artifactsOutOfOrder = List.of(
				artifact("event-2", other), artifact("event-1", produced));
		ExecuteRecordedCommandService wrongOrder = service(recorded(NOW.plusSeconds(60)),
				decoderRegistry(), dispatcher(success(List.of(), producedInOrder)), events(artifactsOutOfOrder));
		assertThrows(IllegalStateException.class, () -> wrongOrder.execute(COMMAND_ID));
	}

	@Test
	void useCaseTechnicalFailureIsPropagatedUnchanged() {
		TechnicalFailure expected = new TechnicalFailure();
		Fixture fixture = new Fixture(Optional.of(recorded(NOW.plusSeconds(60))), authorization -> {
			throw expected;
		});

		assertSame(expected, assertThrows(TechnicalFailure.class, () -> fixture.service.execute(COMMAND_ID)));
		assertEquals(0, fixture.appendCalls.get());
	}

	private static void assertExpired(Fixture fixture) {
		RecordedCommandExecutionResult.Rejected result = assertInstanceOf(
				RecordedCommandExecutionResult.Rejected.class, fixture.service.execute(COMMAND_ID));
		assertEquals(new TerminalReason("AUTHORIZATION_EXPIRED"), result.reason());
		assertEquals(List.of(), result.inputs());
		assertEquals(0, fixture.decodeCalls.get());
		assertEquals(0, fixture.dispatchCalls.get());
		assertEquals(0, fixture.appendCalls.get());
	}

	private static RecordedCommand recorded(Instant validUntil) {
		AuthorizationSnapshot authorization = new AuthorizationSnapshot(new PocomaUserId(UUID.randomUUID()),
				Set.of(), NOW.minusSeconds(10), NOW.minusSeconds(5), validUntil, "issuer");
		return new RecordedCommand(COMMAND_ID, COMMAND_TYPE, "payload", NOW.minusSeconds(1), authorization);
	}

	private static CommandUseCaseResult.Succeeded success(
			List<CommandExecutionInput> inputs,
			List<CommandProducedEvent> events) {
		return new CommandUseCaseResult.Succeeded(inputs, events);
	}

	private static CommandPayloadDecoder<TestCommand> decoder(PayloadDecoder payloadDecoder) {
		return new CommandPayloadDecoder<>() {
			@Override public CommandType commandType() { return COMMAND_TYPE; }
			@Override public Class<TestCommand> commandClass() { return TestCommand.class; }
			@Override public TestCommand decode(String serializedPayload) {
				return payloadDecoder.decode(serializedPayload);
			}
		};
	}

	private static CommandDecoderRegistry decoderRegistry() {
		return new CommandDecoderRegistry(List.of(decoder(TestCommand::new)));
	}

	private static CommandDispatcher dispatcher(CommandUseCaseResult result) {
		return dispatcher(authorization -> result);
	}

	private static CommandDispatcher dispatcher(UseCaseExecution execution) {
		return new CommandDispatcher(List.of(new CommandUseCase<TestCommand>() {
			@Override public Class<TestCommand> commandClass() { return TestCommand.class; }
			@Override public CommandUseCaseResult execute(AuthorizationSnapshot authorization, TestCommand command) {
				return execution.execute(authorization);
			}
		}));
	}

	private static EventAppendPort events(List<CommandExecutionArtifact> artifacts) {
		return ignored -> artifacts;
	}

	private static EventAppendPort eventsThrowing(RuntimeException failure) {
		return ignored -> { throw failure; };
	}

	private static ExecuteRecordedCommandService service(
			RecordedCommand recorded,
			CommandDecoder decoder,
			CommandDispatcher dispatcher,
			EventAppendPort events) {
		return new ExecuteRecordedCommandService(id -> Optional.of(recorded), decoder, dispatcher, events, CLOCK);
	}

	private static CommandExecutionArtifact artifact(String id, CommandProducedEvent event) {
		return new CommandExecutionArtifact("EVENT", event.eventType(), id, OptionalLong.empty(), event.subject(), NOW);
	}

	private record TestCommand(String payload) implements Command {}

	@FunctionalInterface
	private interface PayloadDecoder {
		TestCommand decode(String payload);
	}

	@FunctionalInterface
	private interface UseCaseExecution {
		CommandUseCaseResult execute(AuthorizationSnapshot authorization);
	}

	private static final class TechnicalFailure extends RuntimeException {}

	private static final class Fixture {
		private final AtomicInteger decodeCalls = new AtomicInteger();
		private final AtomicInteger dispatchCalls = new AtomicInteger();
		private final AtomicInteger appendCalls = new AtomicInteger();
		private List<CommandProducedEvent> appendedEvents = List.of();
		private final ExecuteRecordedCommandService service;

		private Fixture(Optional<RecordedCommand> recorded, CommandUseCaseResult result) {
			this(recorded, authorization -> result, List.of());
		}

		private Fixture(Optional<RecordedCommand> recorded, CommandUseCaseResult result,
				List<CommandExecutionArtifact> artifacts) {
			this(recorded, authorization -> result, artifacts);
		}

		private Fixture(Optional<RecordedCommand> recorded, UseCaseExecution execution) {
			this(recorded, execution, List.of());
		}

		private Fixture(Optional<RecordedCommand> recorded, UseCaseExecution execution,
				List<CommandExecutionArtifact> artifacts) {
			CommandDecoder decoder = (type, payload) -> {
				decodeCalls.incrementAndGet();
				return new TestCommand(payload);
			};
			CommandUseCase<TestCommand> useCase = new CommandUseCase<>() {
				@Override public Class<TestCommand> commandClass() { return TestCommand.class; }
				@Override public CommandUseCaseResult execute(AuthorizationSnapshot authorization, TestCommand command) {
					dispatchCalls.incrementAndGet();
					return execution.execute(authorization);
				}
			};
			EventAppendPort events = produced -> {
				appendCalls.incrementAndGet();
				appendedEvents = List.copyOf(produced);
				return artifacts;
			};
			this.service = new ExecuteRecordedCommandService(id -> recorded, decoder,
					new CommandDispatcher(List.of(useCase)), events, CLOCK);
		}
	}
}
