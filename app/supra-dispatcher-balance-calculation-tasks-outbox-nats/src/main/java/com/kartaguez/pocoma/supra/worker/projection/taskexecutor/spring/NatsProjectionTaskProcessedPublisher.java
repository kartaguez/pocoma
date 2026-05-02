package com.kartaguez.pocoma.supra.worker.projection.taskexecutor.spring;

import java.time.Instant;
import java.util.Objects;

import org.springframework.context.event.EventListener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.engine.event.projection.ProjectionTaskProcessedEvent;

final class NatsProjectionTaskProcessedPublisher {

	private static final String PROJECTION_TASK_PROCESSED = "PROJECTION_TASK_PROCESSED";

	private final ProjectionTaskExecutorNatsWakeClient natsClient;
	private final ProjectionTaskExecutorNatsProperties properties;
	private final ObjectMapper objectMapper;

	NatsProjectionTaskProcessedPublisher(
			ProjectionTaskExecutorNatsWakeClient natsClient,
			ProjectionTaskExecutorNatsProperties properties,
			ObjectMapper objectMapper) {
		this.natsClient = Objects.requireNonNull(natsClient, "natsClient must not be null");
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
	}

	@EventListener
	public void on(ProjectionTaskProcessedEvent event) {
		ProjectionTaskProcessedWakePayload payload = new ProjectionTaskProcessedWakePayload(
				PROJECTION_TASK_PROCESSED,
				event.potId().value().toString(),
				event.taskId(),
				event.targetVersion(),
				event.status().name(),
				Instant.now().toString());
		try {
			natsClient.publish(
					properties.getProjectionTasksProcessedSubject(),
					objectMapper.writeValueAsBytes(payload));
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Unable to serialize projection task processed wake signal", e);
		}
	}
}
