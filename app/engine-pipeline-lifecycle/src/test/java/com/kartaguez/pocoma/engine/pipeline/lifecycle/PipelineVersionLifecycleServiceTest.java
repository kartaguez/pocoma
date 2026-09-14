package com.kartaguez.pocoma.engine.pipeline.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinitionRegistry;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pipeline.PipelineVersionDefinition;
import com.kartaguez.pocoma.domain.pipeline.VersionApplicability;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.pipeline.lifecycle.catalog.ProjectionProducerCatalog;
import com.kartaguez.pocoma.engine.pipeline.lifecycle.model.ProjectionProducerBinding;
import com.kartaguez.pocoma.engine.pipeline.lifecycle.model.ServingSelection;
import com.kartaguez.pocoma.engine.port.out.pipeline.lifecycle.PipelineLifecycleStateMutationPort;
import com.kartaguez.pocoma.engine.service.pipeline.lifecycle.InactiveServingPipelineException;
import com.kartaguez.pocoma.engine.service.pipeline.lifecycle.PipelineServingDeactivationException;
import com.kartaguez.pocoma.engine.service.pipeline.lifecycle.PipelineVersionLifecycleService;
import com.kartaguez.pocoma.engine.service.pipeline.lifecycle.ProjectionProducerMismatchException;

class PipelineVersionLifecycleServiceTest {
	private static final PipelineDefinition V1 = new PipelineDefinition(PipelineId.of("read-pot"), 1);
	private static final PipelineDefinition V2 = new PipelineDefinition(PipelineId.of("read-pot"), 2);
	private static final ProjectionType READ_POT = new ProjectionType("READ_POT");
	private static final Instant NOW = Instant.parse("2026-09-14T08:00:00Z");

	@Test void lifecycleChecksDeclaredActiveAndProducerInvariants() {
		var persistence = new FakePersistence();
		var service = service(persistence);

		service.activate(V1);
		assertEquals(List.of(V1), persistence.activated);
		assertThrows(InactiveServingPipelineException.class, () -> service.selectServing(READ_POT, V2));
		assertThrows(ProjectionProducerMismatchException.class,
				() -> service.selectServing(new ProjectionType("OTHER"), V1));

		persistence.servingResult = PipelineLifecycleStateMutationPort.ServingMutationResult.SELECTED;
		service.selectServing(READ_POT, V1);
		assertEquals(new ServingSelection(READ_POT, V1), persistence.selection);
		assertEquals(NOW, persistence.selectedAt);

		persistence.deactivationResult = PipelineLifecycleStateMutationPort.DeactivationResult.SERVING;
		assertThrows(PipelineServingDeactivationException.class, () -> service.deactivate(V1));
	}

	@Test void duplicateProducerBindingIsRejectedButUnboundPipelineIsAllowed() {
		var binding = new ProjectionProducerBinding(READ_POT, V1);
		assertThrows(IllegalArgumentException.class, () -> new ProjectionProducerCatalog(List.of(binding, binding)));
		var catalog = new ProjectionProducerCatalog(List.of(binding));
		assertEquals(false, catalog.produces(V2, READ_POT));
	}

	private PipelineVersionLifecycleService service(FakePersistence persistence) {
		var definitions = new PipelineDefinitionRegistry(List.of(
				new PipelineVersionDefinition(V1, VersionApplicability.from(1)),
				new PipelineVersionDefinition(V2, VersionApplicability.from(1))));
		return new PipelineVersionLifecycleService(definitions,
				new ProjectionProducerCatalog(List.of(
						new ProjectionProducerBinding(READ_POT, V1),
						new ProjectionProducerBinding(READ_POT, V2))),
				persistence, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static final class FakePersistence implements PipelineLifecycleStateMutationPort {
		private final List<PipelineDefinition> activated = new ArrayList<>();
		private DeactivationResult deactivationResult = DeactivationResult.DEACTIVATED;
		private ServingMutationResult servingResult = ServingMutationResult.INACTIVE;
		private ServingSelection selection;
		private Instant selectedAt;

		@Override public void activate(PipelineDefinition pipeline, Instant activatedAt) { activated.add(pipeline); }
		@Override public DeactivationResult deactivateIfNotServing(PipelineDefinition pipeline) {
			return deactivationResult;
		}
		@Override public ServingMutationResult selectServingIfActive(ServingSelection value, Instant at) {
			selection = value;
			selectedAt = at;
			return servingResult;
		}
		@Override public void clearServing(ProjectionType projectionType) {}
	}
}
