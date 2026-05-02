package com.kartaguez.pocoma.supra.worker.projection.spring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

import org.springframework.context.event.EventListener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.engine.event.projection.ProjectionTasksReadyEvent;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWakeSignals;

public class NatsProjectionTasksReadyPublisher {

	private final NatsWakeClient natsClient;
	private final ProjectionNatsProperties properties;
	private final ObjectMapper objectMapper;

	NatsProjectionTasksReadyPublisher(
			NatsWakeClient natsClient,
			ProjectionNatsProperties properties,
			ObjectMapper objectMapper) {
		this.natsClient = Objects.requireNonNull(natsClient, "natsClient must not be null");
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
	}

	@EventListener
	public void on(ProjectionTasksReadyEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		ProjectionWakePayload payload = new ProjectionWakePayload(
				ProjectionWakeSignals.PROJECTION_TASKS_AVAILABLE,
				event.potId().value().toString(),
				Instant.now().toString());
		try {
			natsClient.publish(
					properties.getProjectionTasksAvailableSubject(),
					objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException exception) {
			throw new IllegalStateException("Could not serialize projection tasks NATS wake payload", exception);
		}
	}
}
