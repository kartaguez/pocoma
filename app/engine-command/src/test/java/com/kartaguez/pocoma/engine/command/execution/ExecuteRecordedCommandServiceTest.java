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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.authorization.ExternalAuthorityPermissionTranslator;
import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;
import com.kartaguez.pocoma.domain.event.BusinessEvent;
import com.kartaguez.pocoma.domain.event.EventType;
import com.kartaguez.pocoma.domain.useridentity.BindingAcquireResult;
import com.kartaguez.pocoma.domain.useridentity.BindingDetachResult;
import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.port.binding.authority.ExternalIdentityBindingPort;
import com.kartaguez.pocoma.engine.command.decode.CommandDecoder;
import com.kartaguez.pocoma.engine.command.decode.CommandDecoderRegistry;
import com.kartaguez.pocoma.engine.command.decode.CommandPayloadDecoder;
import com.kartaguez.pocoma.engine.command.decode.InvalidCommandPayloadException;
import com.kartaguez.pocoma.engine.command.decode.UnknownCommandTypeException;
import com.kartaguez.pocoma.engine.command.dispatch.CommandDispatcher;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCase;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.command.dispatch.MissingCommandUseCaseException;
import com.kartaguez.pocoma.engine.command.model.Command;
import com.kartaguez.pocoma.engine.command.model.CommandAppliedResult;
import com.kartaguez.pocoma.engine.command.model.CommandAuthenticationEvidence;
import com.kartaguez.pocoma.engine.command.model.CommandExecutionArtifact;
import com.kartaguez.pocoma.engine.command.model.CommandExecutionAuthorization;
import com.kartaguez.pocoma.engine.command.model.CommandExecutionInput;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.model.CommandType;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.engine.command.model.RecordedCommand;
import com.kartaguez.pocoma.engine.command.model.ResolvedCommandAuthorization;
import com.kartaguez.pocoma.engine.command.model.TargetCommandEnvelope;
import com.kartaguez.pocoma.engine.command.port.out.RecordedCommandPort;
import com.kartaguez.pocoma.engine.command.port.out.EventAppendPort;

class ExecuteRecordedCommandServiceTest {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final CommandId COMMAND_ID = new CommandId(UUID.randomUUID());
	private static final CommandType COMMAND_TYPE = new CommandType("TEST_COMMAND_V1");
	private static final CommandAppliedResult APPLIED = new CommandAppliedResult(UUID.randomUUID(), 1);
	private static final ExternalIdentity EXTERNAL_IDENTITY =
			new ExternalIdentity("https://issuer.example", "subject-1");
	private static final BindingId BINDING_ID = new BindingId(UUID.randomUUID());
	private static final PocomaUserId RESOLVED_USER = new PocomaUserId(UUID.randomUUID());
	private static final com.kartaguez.pocoma.domain.useridentity.BindingRevision REVISION =
			new com.kartaguez.pocoma.domain.useridentity.BindingRevision(7);

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
	void targetV2ObservesAndFencesExactBindingTranslatesAuthoritiesAndDispatchesResolvedUser() {
		RecordingBindings bindings = new RecordingBindings(Optional.of(RESOLVED_USER));
		AtomicReference<CommandExecutionAuthorization> received = new AtomicReference<>();
		ExecuteRecordedCommandService service = service(target(NOW.plusSeconds(60)),
				decoderRegistry(), dispatcher(authorization -> {
					received.set(authorization);
					return success(List.of(), List.of());
				}), events(List.of()), bindings);

		assertInstanceOf(RecordedCommandExecutionResult.Succeeded.class, service.execute(COMMAND_ID));

		ResolvedCommandAuthorization authorization = assertInstanceOf(
				ResolvedCommandAuthorization.class, received.get());
		assertEquals(RESOLVED_USER, authorization.userId());
		assertEquals(Set.of(new Permission("POT", "CREATE")), authorization.permissions());
		assertEquals(1, bindings.observeCalls.get());
		assertEquals(1, bindings.fenceCalls.get());
		assertEquals(0, bindings.nonLockingLookupCalls.get());
		assertEquals(EXTERNAL_IDENTITY, bindings.lockedIdentity.get());
		assertEquals(BINDING_ID, bindings.lockedBinding.get());
	}

	@Test
	void targetV2MissingExactOccurrenceHasOnePublicRejectionAndNoFallback() {
		RecordingBindings bindings = new RecordingBindings(Optional.empty());
		Fixture fixture = new Fixture(Optional.of(target(NOW.plusSeconds(60))),
				success(List.of(), List.of()), List.of(), bindings);

		RecordedCommandExecutionResult.Rejected result = assertInstanceOf(
				RecordedCommandExecutionResult.Rejected.class, fixture.service.execute(COMMAND_ID));

		assertEquals(new TerminalReason("CALLER_IDENTITY_NOT_CURRENT"), result.reason());
		assertEquals(1, bindings.observeCalls.get());
		assertEquals(0, bindings.fenceCalls.get());
		assertEquals(0, bindings.nonLockingLookupCalls.get());
		assertEquals(0, fixture.decodeCalls.get());
		assertEquals(0, fixture.dispatchCalls.get());
		assertEquals(0, fixture.appendCalls.get());
	}

	@Test
	void targetV2ExpirationIsEvaluatedAtWorkerAfterExactBindingResolution() {
		RecordingBindings bindings = new RecordingBindings(Optional.of(RESOLVED_USER));
		Fixture fixture = new Fixture(Optional.of(target(NOW)),
				success(List.of(), List.of()), List.of(), bindings);

		RecordedCommandExecutionResult.Rejected result = assertInstanceOf(
				RecordedCommandExecutionResult.Rejected.class, fixture.service.execute(COMMAND_ID));

		assertEquals(new TerminalReason("AUTHORIZATION_EXPIRED"), result.reason());
		assertEquals(1, bindings.observeCalls.get());
		assertEquals(1, bindings.fenceCalls.get());
		assertEquals(0, fixture.decodeCalls.get());
		assertEquals(0, fixture.dispatchCalls.get());
	}

	@Test
	void targetV2BusinessRejectionIsFencedBeforeReturn() {
		RecordingBindings bindings = new RecordingBindings(Optional.of(RESOLVED_USER));
		var service = service(target(NOW.plusSeconds(60)), decoderRegistry(),
				dispatcher(new CommandUseCaseResult.Rejected(new TerminalReason("BUSINESS_CONFLICT"), List.of())),
				events(List.of()), bindings);
		assertInstanceOf(RecordedCommandExecutionResult.Rejected.class, service.execute(COMMAND_ID));
		assertEquals(1, bindings.fenceCalls.get());
	}

	@Test
	void targetV2LostFenceRaisesTypedFailureAfterBusinessWork() {
		RecordingBindings bindings = new RecordingBindings(Optional.of(RESOLVED_USER));
		bindings.fenceSucceeds = false;
		AtomicInteger business = new AtomicInteger();
		var service = service(target(NOW.plusSeconds(60)), decoderRegistry(), dispatcher(authorization -> {
			business.incrementAndGet();
			return success(List.of(), List.of());
		}), events(List.of()), bindings);
		assertThrows(BindingFenceLostException.class, () -> service.execute(COMMAND_ID));
		assertEquals(1, business.get());
		assertEquals(1, bindings.fenceCalls.get());
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
		TestBusinessEvent first = new TestBusinessEvent("pot-updated");
		TestBusinessEvent second = new TestBusinessEvent("audit-recorded");
		List<BusinessEvent> produced = List.of(first, second);
		List<CommandExecutionArtifact> artifacts = List.of(
				artifact("event-1", "PotUpdated", Optional.of(subject)),
				artifact("event-2", "AuditRecorded", Optional.empty()));
		Fixture fixture = new Fixture(Optional.of(recorded(NOW.plusSeconds(60))),
				success(List.of(subject), produced), artifacts);

		RecordedCommandExecutionResult.Succeeded result = assertInstanceOf(
				RecordedCommandExecutionResult.Succeeded.class, fixture.service.execute(COMMAND_ID));

		assertEquals(1, fixture.appendCalls.get());
		assertEquals(produced, fixture.appendedEvents);
		assertSame(first, fixture.appendedEvents.get(0));
		assertSame(second, fixture.appendedEvents.get(1));
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
		BusinessEvent produced = new TestBusinessEvent("event");
		TechnicalFailure expected = new TechnicalFailure();
		ExecuteRecordedCommandService failing = service(recorded(NOW.plusSeconds(60)),
				decoderRegistry(), dispatcher(success(List.of(), List.of(produced))), eventsThrowing(expected));
		assertSame(expected, assertThrows(TechnicalFailure.class, () -> failing.execute(COMMAND_ID)));

		ExecuteRecordedCommandService nullReport = service(recorded(NOW.plusSeconds(60)),
				decoderRegistry(), dispatcher(success(List.of(), List.of(produced))), ignored -> null);
		assertThrows(CommandExecutionInvariantViolationException.class, () -> nullReport.execute(COMMAND_ID));

		ExecuteRecordedCommandService wrongCount = service(recorded(NOW.plusSeconds(60)),
				decoderRegistry(), dispatcher(success(List.of(), List.of(produced))), events(List.of()));
		assertThrows(CommandExecutionInvariantViolationException.class, () -> wrongCount.execute(COMMAND_ID));
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
		return target(validUntil);
	}

	private static RecordedCommand target(Instant validUntil) {
		return new RecordedCommand(COMMAND_ID, COMMAND_TYPE, "payload", NOW.minusSeconds(1),
				new TargetCommandEnvelope(EXTERNAL_IDENTITY, BINDING_ID,
						new CommandAuthenticationEvidence(
								Set.of("pocoma:pot:create", "provider:ignored"), validUntil)));
	}

	private static CommandUseCaseResult.Succeeded success(
			List<CommandExecutionInput> inputs,
			List<BusinessEvent> events) {
		return new CommandUseCaseResult.Succeeded(inputs, APPLIED, events);
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
			@Override public CommandUseCaseResult execute(CommandExecutionAuthorization authorization, TestCommand command) {
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
		return new ExecuteRecordedCommandService(recordedCommands(Optional.of(recorded)),
				decoder, dispatcher, events, new RecordingBindings(Optional.of(RESOLVED_USER)),
				new ExternalAuthorityPermissionTranslator(), CLOCK);
	}

	private static ExecuteRecordedCommandService service(
			RecordedCommand recorded,
			CommandDecoder decoder,
			CommandDispatcher dispatcher,
			EventAppendPort events,
			ExternalIdentityBindingPort bindings) {
		return new ExecuteRecordedCommandService(recordedCommands(Optional.of(recorded)),
				decoder, dispatcher, events, bindings, new ExternalAuthorityPermissionTranslator(), CLOCK);
	}

	private static CommandExecutionArtifact artifact(
			String id,
			String type,
			Optional<CommandExecutionInput> subject) {
		return new CommandExecutionArtifact("EVENT", type, id, OptionalLong.empty(), subject, NOW);
	}

	private record TestCommand(String payload) implements Command {}
	private record TestBusinessEvent(String change) implements BusinessEvent {
		@Override public EventType eventType() { return new EventType("TEST_EVENT"); }
	}

	@FunctionalInterface
	private interface PayloadDecoder {
		TestCommand decode(String payload);
	}

	@FunctionalInterface
	private interface UseCaseExecution {
		CommandUseCaseResult execute(CommandExecutionAuthorization authorization);
	}

	private static final class TechnicalFailure extends RuntimeException {}

	private static final class Fixture {
		private final AtomicInteger decodeCalls = new AtomicInteger();
		private final AtomicInteger dispatchCalls = new AtomicInteger();
		private final AtomicInteger appendCalls = new AtomicInteger();
		private List<BusinessEvent> appendedEvents = List.of();
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
			this(recorded, execution, artifacts, new RecordingBindings(Optional.of(RESOLVED_USER)));
		}

		private Fixture(Optional<RecordedCommand> recorded, CommandUseCaseResult result,
				List<CommandExecutionArtifact> artifacts, ExternalIdentityBindingPort bindings) {
			this(recorded, authorization -> result, artifacts, bindings);
		}

		private Fixture(Optional<RecordedCommand> recorded, UseCaseExecution execution,
				List<CommandExecutionArtifact> artifacts, ExternalIdentityBindingPort bindings) {
			CommandDecoder decoder = (type, payload) -> {
				decodeCalls.incrementAndGet();
				return new TestCommand(payload);
			};
			CommandUseCase<TestCommand> useCase = new CommandUseCase<>() {
				@Override public Class<TestCommand> commandClass() { return TestCommand.class; }
				@Override public CommandUseCaseResult execute(
						CommandExecutionAuthorization authorization, TestCommand command) {
					dispatchCalls.incrementAndGet();
					return execution.execute(authorization);
				}
			};
			EventAppendPort events = produced -> {
				appendCalls.incrementAndGet();
				appendedEvents = List.copyOf(produced);
				return artifacts;
			};
			this.service = new ExecuteRecordedCommandService(recordedCommands(recorded), decoder,
					new CommandDispatcher(List.of(useCase)), events, bindings,
					new ExternalAuthorityPermissionTranslator(), CLOCK);
		}
	}

	private static final class RecordingBindings implements ExternalIdentityBindingPort {
		private final Optional<PocomaUserId> lockedUser;
		private final AtomicInteger observeCalls = new AtomicInteger();
		private final AtomicInteger fenceCalls = new AtomicInteger();
		private final AtomicInteger nonLockingLookupCalls = new AtomicInteger();
		private final AtomicReference<ExternalIdentity> lockedIdentity = new AtomicReference<>();
		private final AtomicReference<BindingId> lockedBinding = new AtomicReference<>();
		private boolean fenceSucceeds = true;

		private RecordingBindings(Optional<PocomaUserId> lockedUser) {
			this.lockedUser = lockedUser;
		}

		@Override public Optional<PocomaUserId> findUserId(ExternalIdentity identity, BindingId bindingId) {
			nonLockingLookupCalls.incrementAndGet();
			throw new AssertionError("Initial TARGET_V2 observation must include revision");
		}

		@Override public Optional<com.kartaguez.pocoma.domain.useridentity.ObservedBinding> observeCurrentBinding(
				ExternalIdentity identity, BindingId bindingId) {
			observeCalls.incrementAndGet();
			lockedIdentity.set(identity);
			lockedBinding.set(bindingId);
			return lockedUser.map(user -> new com.kartaguez.pocoma.domain.useridentity.ObservedBinding(user, REVISION));
		}

		@Override public boolean fenceObservedBinding(ExternalIdentity identity, PocomaUserId userId,
				BindingId bindingId, com.kartaguez.pocoma.domain.useridentity.BindingRevision revision) {
			fenceCalls.incrementAndGet();
			assertEquals(REVISION, revision);
			assertEquals(lockedUser.orElseThrow(), userId);
			return fenceSucceeds;
		}

		@Override public BindingAcquireResult acquire(
				ExternalIdentity identity, PocomaUserId userId) {
			throw new UnsupportedOperationException("not used by Command execution");
		}
		@Override public BindingAcquireResult acquireWithInitializer(
				ExternalIdentity identity, PocomaUserId userId, Runnable initializer) {
			throw new UnsupportedOperationException("not used by Command execution");
		}

		@Override public BindingDetachResult detach(ExternalIdentity identity, BindingId bindingId) {
			throw new UnsupportedOperationException("not used by Command execution");
		}
	}

	private static RecordedCommandPort recordedCommands(Optional<RecordedCommand> recorded) {
		return new RecordedCommandPort() {
			@Override public void insert(RecordedCommand command) {
				throw new UnsupportedOperationException("not used by execution tests");
			}
			@Override public Optional<RecordedCommand> findById(CommandId commandId) { return recorded; }
		};
	}
}
