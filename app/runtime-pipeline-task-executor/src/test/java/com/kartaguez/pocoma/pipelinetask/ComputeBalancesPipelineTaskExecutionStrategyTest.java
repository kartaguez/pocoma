package com.kartaguez.pocoma.pipelinetask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.engine.taskexecution.model.LegacyPipelineTask;
import com.kartaguez.pocoma.domain.task.TaskPayload;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.taskexecution.input.ExecuteTaskInput;
import com.kartaguez.pocoma.engine.port.in.taskexecution.usecase.ExecuteTaskUseCase;

class ComputeBalancesPipelineTaskExecutionStrategyTest {

	@Test
	void mapsLegacyJsonAndPreservesRoutingMetadata() {
		RecordingExecuteTask useCase = new RecordingExecuteTask();
		var strategy = new ComputeBalancesPipelineTaskExecutionStrategy(useCase, new ObjectMapper());
		PotId potId = PotId.of(UUID.randomUUID());
		LegacyPipelineTask durableTask = task("{\"potId\":\"" + potId.value() + "\",\"targetVersion\":5}");

		strategy.execute(durableTask);

		ExecuteTaskInput<?> input = useCase.inputs.getFirst();
		assertEquals(durableTask.pipeline(), input.pipeline());
		assertEquals(durableTask.taskType(), input.taskType());
		assertEquals(new ComputeBalancesTask(potId, 5), input.task());
	}

	@Test
	void rejectsInvalidJsonBeforeCallingTypedUseCase() {
		RecordingExecuteTask useCase = new RecordingExecuteTask();
		var strategy = new ComputeBalancesPipelineTaskExecutionStrategy(useCase, new ObjectMapper());

		assertThrows(IllegalArgumentException.class, () -> strategy.execute(task("not-json")));
		assertEquals(List.of(), useCase.inputs);
	}

	@Test
	void propagatesTypedExecutionFailureUnchanged() {
		RecordingExecuteTask useCase = new RecordingExecuteTask();
		RuntimeException failure = new IllegalStateException("boom");
		useCase.failure = failure;
		var strategy = new ComputeBalancesPipelineTaskExecutionStrategy(useCase, new ObjectMapper());
		PotId potId = PotId.of(UUID.randomUUID());

		assertSame(failure, assertThrows(RuntimeException.class,
				() -> strategy.execute(task("{\"potId\":\"" + potId.value() + "\",\"targetVersion\":1}"))));
	}

	private static LegacyPipelineTask task(String payload) {
		PotId potId = PotId.of(UUID.randomUUID());
		return new LegacyPipelineTask(
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				ComputeBalancesPipelineTaskExecutionStrategy.DEFINITION,
				ComputeBalancesPipelineTaskExecutionStrategy.TASK_TYPE,
				"task-key", payload, potId.value().toString(), potId, null, null, Instant.now(), 0L);
	}

	private static final class RecordingExecuteTask implements ExecuteTaskUseCase {
		private final List<ExecuteTaskInput<? extends TaskPayload>> inputs = new ArrayList<>();
		private RuntimeException failure;

		@Override
		public void executeTask(ExecuteTaskInput<? extends TaskPayload> input) {
			if (failure != null) {
				throw failure;
			}
			inputs.add(input);
		}
	}
}
