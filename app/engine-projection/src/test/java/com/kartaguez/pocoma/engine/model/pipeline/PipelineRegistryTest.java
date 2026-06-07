package com.kartaguez.pocoma.engine.model.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.engine.model.BusinessEventEnvelope;

class PipelineRegistryTest {

	@Test
	void exposesActivePipelinesAndFindsStrategy() {
		PipelineStrategy strategy = new TestStrategy(new PipelineDefinition(PipelineId.of("test"), 1));
		PipelineRegistry registry = new PipelineRegistry(List.of(strategy));

		assertEquals(List.of(strategy.definition()), registry.activePipelines());
		assertEquals(strategy, registry.find(strategy.definition()).orElseThrow());
	}

	@Test
	void rejectsDuplicateDefinitions() {
		PipelineDefinition definition = new PipelineDefinition(PipelineId.of("test"), 1);

		assertThrows(IllegalArgumentException.class, () -> new PipelineRegistry(List.of(
				new TestStrategy(definition),
				new TestStrategy(definition))));
	}

	@Test
	void returnsEmptyWhenStrategyIsUnknown() {
		PipelineRegistry registry = new PipelineRegistry(List.of());

		assertTrue(registry.find(new PipelineDefinition(PipelineId.of("missing"), 1)).isEmpty());
	}

	private record TestStrategy(PipelineDefinition definition) implements PipelineStrategy {
		@Override
		public boolean supports(BusinessEventEnvelope event) {
			return true;
		}

		@Override
		public List<TaskDescriptor> materializeTasks(BusinessEventEnvelope event) {
			return List.of();
		}
	}
}
