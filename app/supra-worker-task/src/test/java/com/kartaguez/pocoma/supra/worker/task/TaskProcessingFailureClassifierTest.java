package com.kartaguez.pocoma.supra.worker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.engine.exception.MissingTaskExecutionHandlerException;
import com.kartaguez.pocoma.engine.exception.TaskExecutionRejectedException;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.supra.worker.task.mapping.RecordedTaskMappingException;

class TaskProcessingFailureClassifierTest {
	private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");
	private static final PipelineDefinition PIPELINE = new PipelineDefinition(PipelineId.of("balances"), 1);
	private final TaskProcessingFailureClassifier classifier =
			TaskProcessingFailureClassifier.conservative(Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void classifiesOnlyExplicitTerminalFailures() {
		var payload = classifier.classify(new RecordedTaskMappingException(
				RecordedTaskMappingException.INVALID_TASK_PAYLOAD, "invalid payload")).orElseThrow();
		assertEquals("INVALID_TASK_PAYLOAD", payload.category());
		assertEquals(NOW, payload.occurredAt());

		var rejected = classifier.classify(new TaskExecutionRejectedException("rejected")).orElseThrow();
		assertEquals("TASK_EXECUTION_REJECTED", rejected.category());

		assertTrue(classifier.classify(new RecordedTaskMappingException(
				RecordedTaskMappingException.MISSING_TASK_MAPPER, "missing mapper")).isEmpty());
		assertTrue(classifier.classify(new MissingTaskExecutionHandlerException(PIPELINE, "A")).isEmpty());
		assertTrue(classifier.classify(new IllegalStateException("database unavailable")).isEmpty());
	}
}
