package com.kartaguez.pocoma.supra.worker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;

class TaskWorkerSettingsTest {
	private static final PipelineDefinition PIPELINE = new PipelineDefinition(PipelineId.of("balances"), 1);

	@Test
	void acceptsTheExactThreeTimesBoundaryAndConvertsSettings() {
		TaskWorkerSettings settings = settings(Duration.ofSeconds(30), Duration.ofSeconds(10));
		assertEquals("task-1", settings.consumptionWorkerId().value());
		assertEquals(Duration.ofSeconds(30), settings.claimLease().duration());
		assertEquals(PIPELINE, settings.pipeline());
	}

	@Test
	void rejectsInvalidSettings() {
		assertThrows(IllegalArgumentException.class,
				() -> settings(Duration.ofSeconds(29), Duration.ofSeconds(10)));
		assertThrows(IllegalArgumentException.class,
				() -> new TaskWorkerSettings(true, " ", Duration.ofSeconds(1), Duration.ofSeconds(30),
						Duration.ofSeconds(10), WorkerSegment.single(), PIPELINE, false));
		assertThrows(IllegalArgumentException.class,
				() -> new TaskWorkerSettings(true, "task-1", Duration.ZERO, Duration.ofSeconds(30),
						Duration.ofSeconds(10), WorkerSegment.single(), PIPELINE, false));
		assertThrows(NullPointerException.class,
				() -> new TaskWorkerSettings(true, "task-1", Duration.ofSeconds(1), Duration.ofSeconds(30),
						Duration.ofSeconds(10), null, PIPELINE, false));
		assertThrows(IllegalArgumentException.class,
				() -> settings(Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(Long.MAX_VALUE)));
	}

	private static TaskWorkerSettings settings(Duration lease, Duration normal) {
		return new TaskWorkerSettings(true, "task-1", Duration.ofSeconds(1), lease, normal,
				WorkerSegment.single(), PIPELINE, false);
	}
}
