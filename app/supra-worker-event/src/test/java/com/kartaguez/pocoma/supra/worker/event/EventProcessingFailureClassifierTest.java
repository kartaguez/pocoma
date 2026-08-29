package com.kartaguez.pocoma.supra.worker.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.engine.exception.MissingTaskCreationStrategyException;
import com.kartaguez.pocoma.engine.exception.TaskCreationRejectedException;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;

class EventProcessingFailureClassifierTest {

	private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");
	private final EventProcessingFailureClassifier classifier =
			EventProcessingFailureClassifier.conservative(Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void classifiesOnlyExplicitDeterministicRejections() {
		var failure = classifier.classify(new TaskCreationRejectedException("unsupported event")).orElseThrow();

		assertEquals("TASK_CREATION_REJECTED", failure.category());
		assertEquals("unsupported event", failure.message());
		assertEquals(NOW, failure.occurredAt());
		assertTrue(classifier.classify(new IllegalStateException("database unavailable")).isEmpty());
		assertTrue(classifier.classify(new MissingTaskCreationStrategyException(
				new PipelineDefinition(PipelineId.of("missing"), 1))).isEmpty());
	}
}
