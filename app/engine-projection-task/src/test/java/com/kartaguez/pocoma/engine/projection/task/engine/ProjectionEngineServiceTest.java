package com.kartaguez.pocoma.engine.projection.task.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;
import com.kartaguez.pocoma.domain.projection.Projection;
import com.kartaguez.pocoma.domain.projection.ProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.domain.projection.ProjectionValidationException;
import com.kartaguez.pocoma.domain.projection.ProjectionValidator;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.projection.TargetObjectType;
import com.kartaguez.pocoma.engine.projection.task.ProjectionPreparationInvariantViolationException;
import com.kartaguez.pocoma.engine.projection.task.ProjectionPreparationOutcome.Prepared;
import com.kartaguez.pocoma.engine.projection.task.ProjectionPreparationOutcome.Temporary;
import com.kartaguez.pocoma.engine.projection.task.ProjectionPreparationOutcome.Terminal;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTask;
import com.kartaguez.pocoma.engine.projection.task.TemporaryProjectionPreparationException;
import com.kartaguez.pocoma.engine.projection.task.TerminalProjectionPreparationException;

class ProjectionEngineServiceTest {
	private static final ProjectionType TYPE = new ProjectionType("READ_POT");
	private static final TargetObjectType TARGET = new TargetObjectType("POT");
	private static final ProjectionKey KEY = new ProjectionKey(TYPE, TARGET, new TargetObjectId("pot-1"), 42);
	private static final ProjectionDefinition DEFINITION = new ProjectionDefinition(TYPE, TARGET, List.of());

	@Test
	void resolvesLoadsProjectsChecksTheKeyAndValidates() {
		var loaded = new Object();
		var loaderCalled = new AtomicBoolean();
		var projectorCalled = new AtomicBoolean();
		var declaration = declaration(key -> {
			assertEquals(KEY, key);
			loaderCalled.set(true);
			return loaded;
		}, (key, input) -> {
			assertSame(loaded, input);
			projectorCalled.set(true);
			return new Projection(key, List.of());
		});

		var result = assertInstanceOf(Prepared.class, engine(declaration).execute(new ProjectionTask(KEY)));

		assertEquals(KEY, result.projection().projection().projectionKey());
		assertEquals(true, loaderCalled.get());
		assertEquals(true, projectorCalled.get());
	}

	@Test
	void rejectsAnUnconfiguredProjectionTypeAsAnInternalInvariant() {
		var configuredType = new ProjectionType("OTHER");
		var configuredDefinition = new ProjectionDefinition(configuredType, TARGET, List.of());
		var declaration = new ProjectionProducerDeclaration<>(configuredType, TARGET, configuredDefinition,
				key -> new Object(), (key, input) -> new Projection(key, List.of()));

		var exception = assertThrows(ProjectionPreparationInvariantViolationException.class,
				() -> engine(declaration).execute(new ProjectionTask(KEY)));

		assertEquals(KEY, exception.projectionKey());
	}

	@Test
	void rejectsARequestedTargetObjectTypeNotSupportedByTheProducerBeforeLoading() {
		var loaderCalled = new AtomicBoolean();
		var declaration = declaration(key -> {
			loaderCalled.set(true);
			return new Object();
		}, (key, input) -> new Projection(key, List.of()));
		var wrongTargetKey = new ProjectionKey(TYPE, new TargetObjectType("USER"),
				new TargetObjectId("pot-1"), 42);

		assertThrows(ProjectionPreparationInvariantViolationException.class,
				() -> engine(declaration).execute(new ProjectionTask(wrongTargetKey)));
		assertEquals(false, loaderCalled.get());
	}

	@Test
	void mapsOnlyExplicitlyClassifiedTemporaryAndTerminalFailuresToNormalOutcomes() {
		var temporary = failure("NOT_READY");
		var temporaryDeclaration = declaration(key -> {
			throw new TemporaryProjectionPreparationException(temporary, null);
		}, (key, input) -> new Projection(key, List.of()));
		assertSame(temporary, assertInstanceOf(Temporary.class,
				engine(temporaryDeclaration).execute(new ProjectionTask(KEY))).failure());

		var terminal = failure("IMPOSSIBLE");
		var terminalDeclaration = declaration(key -> {
			throw new TerminalProjectionPreparationException(terminal, null);
		}, (key, input) -> new Projection(key, List.of()));
		assertSame(terminal, assertInstanceOf(Terminal.class,
				engine(terminalDeclaration).execute(new ProjectionTask(KEY))).failure());
	}

	@Test
	void rejectsAProjectionProducedForAnotherExactKeyBeforeValidation() {
		var anotherKey = new ProjectionKey(TYPE, TARGET, new TargetObjectId("pot-2"), 42);
		var declaration = declaration(key -> new Object(),
				(key, input) -> new Projection(anotherKey, List.of()));

		var exception = assertThrows(ProjectionPreparationInvariantViolationException.class,
				() -> engine(declaration).execute(new ProjectionTask(KEY)));

		assertEquals(KEY, exception.projectionKey());
	}

	@Test
	void exposesSchemaValidationFailureAsAnInternalInvariant() {
		var wrongDefinition = new ProjectionDefinition(TYPE, TARGET,
				List.of(new com.kartaguez.pocoma.domain.projection.ArtifactDefinition(
						new com.kartaguez.pocoma.domain.projection.ArtifactType("REQUIRED"),
						new com.kartaguez.pocoma.domain.projection.Cardinality(1, 1),
						new com.kartaguez.pocoma.domain.projection.JsonObject(java.util.Map.of()))));
		var declaration = new ProjectionProducerDeclaration<>(TYPE, TARGET, wrongDefinition,
				key -> new Object(), (key, input) -> new Projection(key, List.of()));

		var exception = assertThrows(ProjectionPreparationInvariantViolationException.class,
				() -> engine(declaration).execute(new ProjectionTask(KEY)));

		assertInstanceOf(ProjectionValidationException.class, exception.getCause());
	}

	@Test
	void catalogSupportsSeveralProducersAndRejectsDuplicatesOrIncoherentDeclarations() {
		var read = declaration(key -> new Object(), (key, input) -> new Projection(key, List.of()));
		var otherType = new ProjectionType("POT_BALANCES");
		var otherDefinition = new ProjectionDefinition(otherType, TARGET, List.of());
		var balances = new ProjectionProducerDeclaration<>(otherType, TARGET, otherDefinition,
				key -> new Object(), (key, input) -> new Projection(key, List.of()));

		var catalog = new ProjectionProducerCatalog(List.of(read, balances));
		assertSame(read, catalog.find(TYPE).orElseThrow());
		assertSame(balances, catalog.find(otherType).orElseThrow());
		assertThrows(IllegalArgumentException.class,
				() -> new ProjectionProducerCatalog(List.of(read, read)));
		assertThrows(IllegalArgumentException.class,
				() -> new ProjectionProducerDeclaration<>(otherType, TARGET, DEFINITION,
						key -> new Object(), (key, input) -> new Projection(key, List.of())));
	}

	private static ProjectionProducerDeclaration<Object> declaration(
			ProjectionInputLoader<Object> loader, ProjectionProjector<Object> projector) {
		return new ProjectionProducerDeclaration<>(TYPE, TARGET, DEFINITION, loader, projector);
	}

	private static ProjectionEngineService engine(ProjectionProducerDeclaration<?>... declarations) {
		return new ProjectionEngineService(new ProjectionProducerCatalog(List.of(declarations)),
				new ProjectionValidator((schema, payload) -> true));
	}

	private static ProcessingFailure failure(String code) {
		return new ProcessingFailure(new ProcessingFailureCode(code), "projection", code,
				Instant.parse("2026-09-20T10:00:00Z"));
	}
}
