package com.kartaguez.pocoma.engine.service.query.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinitionRegistry;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pipeline.PipelineVersionDefinition;
import com.kartaguez.pocoma.domain.pipeline.VersionApplicability;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.pipeline.lifecycle.model.ServingSelection;

class ServingQueryProjectionSelectionProviderTest {
	@Test void providesOnlyTheExplicitServingGeneration() {
		var type = new ProjectionType("READ_POT");
		var v2 = new PipelineDefinition(PipelineId.of("read-pot"), 2);
		var v3 = new PipelineDefinition(PipelineId.of("read-pot"), 3);
		var definitions = new PipelineDefinitionRegistry(List.of(
				new PipelineVersionDefinition(v2, VersionApplicability.from(1)),
				new PipelineVersionDefinition(v3, VersionApplicability.from(1))));
		var provider = new ServingQueryProjectionSelectionProvider(
				requested -> Optional.of(new ServingSelection(requested, v2)), definitions);

		var result = provider.findServingSelection(type).orElseThrow();
		assertEquals(v2, result.servingPipeline().identity());
	}

	@Test void absenceStaysExplicitWithoutFallback() {
		var provider = new ServingQueryProjectionSelectionProvider(
				requested -> Optional.empty(), new PipelineDefinitionRegistry(List.of()));
		assertTrue(provider.findServingSelection(new ProjectionType("READ_POT")).isEmpty());
	}
}
