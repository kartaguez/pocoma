package com.kartaguez.pocoma.engine.taskmaterialization.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineId;
import com.kartaguez.pocoma.domain.pipeline.task.TaskDescriptor;
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
	void exposesOnlyConfiguredEnabledPipelines() {
		PipelineStrategy enabled = new TestStrategy(new PipelineDefinition(PipelineId.of("enabled"), 1));
		PipelineStrategy disabled = new TestStrategy(new PipelineDefinition(PipelineId.of("disabled"), 1));

		PipelineRegistry registry = new PipelineRegistry(
				List.of(enabled, disabled),
				List.of(
						new ConfiguredPipelineBinding(enabled.definition(), List.of("PotCreatedEvent"), true),
						new ConfiguredPipelineBinding(disabled.definition(), List.of("PotCreatedEvent"), false)));

		assertEquals(List.of(enabled.definition()), registry.activePipelines());
		assertEquals(List.of(new ConfiguredPipelineBinding(enabled.definition(), List.of("PotCreatedEvent"), true)),
				registry.activeBindings());
	}

	@Test
	void rejectsEnabledBindingWithoutStrategy() {
		ConfiguredPipelineBinding binding = new ConfiguredPipelineBinding(
				new PipelineDefinition(PipelineId.of("missing"), 1),
				List.of("PotCreatedEvent"),
				true);

		assertThrows(IllegalArgumentException.class, () -> new PipelineRegistry(List.of(), List.of(binding)));
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
