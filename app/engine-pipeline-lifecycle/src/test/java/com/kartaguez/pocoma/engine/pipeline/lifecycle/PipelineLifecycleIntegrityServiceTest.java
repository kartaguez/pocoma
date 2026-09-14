package com.kartaguez.pocoma.engine.pipeline.lifecycle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinitionRegistry;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pipeline.PipelineVersionDefinition;
import com.kartaguez.pocoma.domain.pipeline.VersionApplicability;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.pipeline.lifecycle.catalog.ProjectionProducerCatalog;
import com.kartaguez.pocoma.engine.pipeline.lifecycle.model.PipelineLifecycleStateSnapshot;
import com.kartaguez.pocoma.engine.pipeline.lifecycle.model.ProjectionProducerBinding;
import com.kartaguez.pocoma.engine.pipeline.lifecycle.model.ServingSelection;
import com.kartaguez.pocoma.engine.service.pipeline.lifecycle.PipelineLifecycleIntegrityService;
import com.kartaguez.pocoma.engine.service.pipeline.lifecycle.ProjectionProducerMismatchException;

class PipelineLifecycleIntegrityServiceTest {
	private static final PipelineDefinition DECLARED =
			new PipelineDefinition(PipelineId.of("read-pot"), 1);
	private static final PipelineDefinition ORPHAN =
			new PipelineDefinition(PipelineId.of("read-pot"), 99);
	private static final ProjectionType READ_POT = new ProjectionType("READ_POT");

	@Test
	void acceptsDeclaredActiveCompatibleServingState() {
		assertDoesNotThrow(() -> service(new PipelineLifecycleStateSnapshot(
				List.of(DECLARED), List.of(new ServingSelection(READ_POT, DECLARED)))).validate());
	}

	@Test
	void rejectsOrphanActivationAndServingWithoutActivation() {
		assertThrows(RuntimeException.class,
				() -> service(new PipelineLifecycleStateSnapshot(List.of(ORPHAN), List.of())).validate());
		assertThrows(IllegalStateException.class,
				() -> service(new PipelineLifecycleStateSnapshot(
						List.of(), List.of(new ServingSelection(READ_POT, DECLARED)))).validate());
	}

	@Test
	void rejectsServingWhoseProducerDoesNotMatchProjection() {
		var other = new ProjectionType("OTHER");
		assertThrows(ProjectionProducerMismatchException.class,
				() -> service(new PipelineLifecycleStateSnapshot(
						List.of(DECLARED), List.of(new ServingSelection(other, DECLARED)))).validate());
	}

	private static PipelineLifecycleIntegrityService service(PipelineLifecycleStateSnapshot snapshot) {
		var definitions = new PipelineDefinitionRegistry(List.of(
				new PipelineVersionDefinition(DECLARED, VersionApplicability.from(1))));
		var producers = new ProjectionProducerCatalog(List.of(new ProjectionProducerBinding(READ_POT, DECLARED)));
		return new PipelineLifecycleIntegrityService(definitions, producers, () -> snapshot);
	}
}
