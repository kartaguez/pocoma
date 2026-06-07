package com.kartaguez.pocoma.engine.taskexecution.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pipeline.task.ConfiguredTaskExecutionBinding;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineId;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineTask;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.taskexecution.model.PipelineTaskExecutionRegistry;
import com.kartaguez.pocoma.engine.taskexecution.model.PipelineTaskExecutionStrategy;
import com.kartaguez.pocoma.engine.taskexecution.port.in.ExecutePipelineTaskCommand;

class ExecutePipelineTaskServiceTest {

	@Test
	void executesActiveStrategy() {
		PipelineDefinition definition = definition("balance-projection");
		RecordingStrategy strategy = new RecordingStrategy(definition, "COMPUTE_BALANCES_FOR_VERSION");
		ExecutePipelineTaskService service = new ExecutePipelineTaskService(
				new PipelineTaskExecutionRegistry(List.of(strategy), List.of(new ConfiguredTaskExecutionBinding(
						definition,
						List.of("COMPUTE_BALANCES_FOR_VERSION"),
						true))));
		PipelineTask task = task(definition, "COMPUTE_BALANCES_FOR_VERSION");

		service.executeTask(new ExecutePipelineTaskCommand(task));

		assertEquals(List.of(task), strategy.executedTasks);
	}

	@Test
	void rejectsDisabledBinding() {
		PipelineDefinition definition = definition("balance-projection");
		ExecutePipelineTaskService service = new ExecutePipelineTaskService(
				new PipelineTaskExecutionRegistry(List.of(new RecordingStrategy(
						definition,
						"COMPUTE_BALANCES_FOR_VERSION")), List.of(new ConfiguredTaskExecutionBinding(
								definition,
								List.of("COMPUTE_BALANCES_FOR_VERSION"),
								false))));

		assertThrows(IllegalArgumentException.class, () -> service.executeTask(new ExecutePipelineTaskCommand(
				task(definition, "COMPUTE_BALANCES_FOR_VERSION"))));
	}

	@Test
	void propagatesStrategyFailure() {
		PipelineDefinition definition = definition("balance-projection");
		RecordingStrategy strategy = new RecordingStrategy(definition, "COMPUTE_BALANCES_FOR_VERSION");
		strategy.failure = new IllegalStateException("boom");
		ExecutePipelineTaskService service = new ExecutePipelineTaskService(
				new PipelineTaskExecutionRegistry(List.of(strategy)));

		assertThrows(IllegalStateException.class, () -> service.executeTask(new ExecutePipelineTaskCommand(
				task(definition, "COMPUTE_BALANCES_FOR_VERSION"))));
	}

	private static PipelineDefinition definition(String pipelineId) {
		return new PipelineDefinition(PipelineId.of(pipelineId), 1);
	}

	private static PipelineTask task(PipelineDefinition definition, String taskType) {
		PotId potId = PotId.of(UUID.randomUUID());
		return new PipelineTask(
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				definition,
				taskType,
				"task-1",
				"{}",
				potId.value().toString(),
				potId,
				null,
				null,
				Instant.now(),
				System.nanoTime());
	}

	private static final class RecordingStrategy implements PipelineTaskExecutionStrategy {
		private final PipelineDefinition definition;
		private final String taskType;
		private final List<PipelineTask> executedTasks = new ArrayList<>();
		private RuntimeException failure;

		private RecordingStrategy(PipelineDefinition definition, String taskType) {
			this.definition = definition;
			this.taskType = taskType;
		}

		@Override
		public PipelineDefinition definition() {
			return definition;
		}

		@Override
		public boolean supports(String taskType) {
			return this.taskType.equals(taskType);
		}

		@Override
		public void execute(PipelineTask task) {
			if (failure != null) {
				throw failure;
			}
			executedTasks.add(task);
		}
	}
}
