package com.kartaguez.pocoma.engine.projection.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;
import com.kartaguez.pocoma.domain.projection.Projection;
import com.kartaguez.pocoma.domain.projection.ProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionFailure;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.domain.projection.ProjectionValidator;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.projection.TargetObjectType;
import com.kartaguez.pocoma.domain.projection.ValidatedProjection;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization.Success;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization.TerminalFailure;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureContext;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision.RetryAfter;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.engine.port.out.projection.ProjectionPublicationResult;
import com.kartaguez.pocoma.engine.port.out.projection.ProjectionWritePort;

class ProjectionTaskConsumptionServiceTest {
	private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");
	private static final ProjectionKey KEY = new ProjectionKey(new ProjectionType("READ_POT"),
			new TargetObjectType("POT"), new TargetObjectId("pot-1"), 42);
	private static final Claim CLAIM = Claim.active(new ClaimId(java.util.UUID.randomUUID()), java.util.UUID.randomUUID(),
			new WorkerId("worker"), 5, NOW.minusSeconds(60), new ClaimLease(Duration.ofSeconds(30)));

	@Test
	void validatesBeforePublishingAndTreatsAlreadyExistsAsSuccess() {
		ValidatedProjection validated = validated(KEY);
		var writer = new RecordingWriter(ProjectionPublicationResult.ALREADY_EXISTS);
		var finalized = new AtomicBoolean();
		var service = service(ignored -> new ProjectionPreparationOutcome.Prepared(validated), writer,
				input -> {
					assertTrue(input.finalization() instanceof Success);
					input.durableEffect().apply();
					finalized.set(true);
					return FencedMutationResult.APPLIED;
				}, input -> { throw new AssertionError("retry must not be used"); });

		assertEquals(ProjectionTaskExecutionResult.FINALIZED, service.execute(new ProjectionTask(KEY), CLAIM));
		assertSame(validated, writer.published.get());
		assertTrue(finalized.get());
	}

	@Test
	void aTemporaryFailureOnlySchedulesRetryEvenAfterManyAttempts() {
		ProcessingFailure failure = failure("DEPENDENCY_NOT_READY");
		var observed = new AtomicReference<ProcessingFailure>();
		var writer = new RecordingWriter(ProjectionPublicationResult.PUBLISHED);
		var highAttemptClaim = Claim.active(new ClaimId(java.util.UUID.randomUUID()), java.util.UUID.randomUUID(),
				new WorkerId("worker"), 400, NOW, new ClaimLease(Duration.ofSeconds(30)));
		var service = service(ignored -> new ProjectionPreparationOutcome.Temporary(failure), writer,
				input -> { throw new AssertionError("terminal finalization must not be used"); }, input -> {
					observed.set(input.failure());
					return FencedMutationResult.APPLIED;
				});

		assertEquals(ProjectionTaskExecutionResult.RETRY_SCHEDULED,
				service.execute(new ProjectionTask(KEY), highAttemptClaim));
		assertSame(failure, observed.get());
		assertFalse(writer.called());
	}

	@Test
	void aTerminalProjectionFailureIsRecordedInsideFencedFinalization() {
		ProcessingFailure failure = failure("IMPOSSIBLE_PROJECTION");
		var writer = new RecordingWriter(ProjectionPublicationResult.PUBLISHED);
		var service = service(ignored -> new ProjectionPreparationOutcome.Terminal(failure), writer, input -> {
			assertTrue(input.finalization() instanceof TerminalFailure);
			assertSame(failure, ((TerminalFailure) input.finalization()).failure());
			input.durableEffect().apply();
			return FencedMutationResult.APPLIED;
		}, input -> { throw new AssertionError("retry must not be used"); });

		assertEquals(ProjectionTaskExecutionResult.FINALIZED, service.execute(new ProjectionTask(KEY), CLAIM));
		assertEquals(KEY, writer.failure.get().projectionKey());
		assertEquals(NOW, writer.failure.get().failedAt());
	}

	@Test
	void internalInvariantFailuresAreNeitherRetriedNorRecordedAsProjectionFailures() {
		var writer = new RecordingWriter(ProjectionPublicationResult.PUBLISHED);
		var service = service(ignored -> { throw new ProjectionPreparationInvariantViolationException(KEY, "bug", null); },
				writer, input -> { throw new AssertionError(); }, input -> { throw new AssertionError(); });

		assertThrows(ProjectionPreparationInvariantViolationException.class,
				() -> service.execute(new ProjectionTask(KEY), CLAIM));
		assertFalse(writer.called());
	}

	@Test
	void retryPolicyNeverPromotesAHighAttemptToTerminal() {
		var policy = new ProjectionTaskRetryPolicy(attempt -> Duration.ofSeconds(attempt));
		var decision = policy.decide(new FailureContext(failure("TEMP"), 400, NOW));

		assertEquals(new RetryAfter(Duration.ofSeconds(400)), decision);
	}

	private static ProjectionTaskConsumptionService service(
			com.kartaguez.pocoma.engine.projection.task.engine.ExecuteProjectionTaskUseCase projectionEngine,
			ProjectionWritePort writer,
			com.kartaguez.pocoma.engine.port.in.consumption.usecase.FinalizeConsumptionUseCase finalizer,
			com.kartaguez.pocoma.engine.port.in.consumption.usecase.HandleConsumptionFailureUseCase retry) {
		return new ProjectionTaskConsumptionService(projectionEngine, writer, finalizer, retry,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static ValidatedProjection validated(ProjectionKey key) {
		var definition = new ProjectionDefinition(key.projectionType(), key.targetObjectType(), List.of());
		return new ProjectionValidator((schema, payload) -> true).validate(definition, new Projection(key, List.of()));
	}

	private static ProcessingFailure failure(String code) {
		return new ProcessingFailure(new ProcessingFailureCode(code), "projection", code, NOW);
	}

	private static final class RecordingWriter implements ProjectionWritePort {
		private final ProjectionPublicationResult publicationResult;
		private final AtomicReference<ValidatedProjection> published = new AtomicReference<>();
		private final AtomicReference<ProjectionFailure> failure = new AtomicReference<>();

		private RecordingWriter(ProjectionPublicationResult publicationResult) {
			this.publicationResult = publicationResult;
		}
		@Override public ProjectionPublicationResult publish(ValidatedProjection projection) {
			published.set(projection);
			return publicationResult;
		}
		@Override public void recordFailure(ProjectionFailure value) { failure.set(value); }
		private boolean called() { return published.get() != null || failure.get() != null; }
	}
}
