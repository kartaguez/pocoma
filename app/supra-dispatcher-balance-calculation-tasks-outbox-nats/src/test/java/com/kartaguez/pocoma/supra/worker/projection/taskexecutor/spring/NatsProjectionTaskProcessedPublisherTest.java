package com.kartaguez.pocoma.supra.worker.projection.taskexecutor.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.event.projection.ProjectionTaskProcessedEvent;
import com.kartaguez.pocoma.engine.model.ProjectionTaskStatus;

class NatsProjectionTaskProcessedPublisherTest {

	@Test
	void publishesProjectionTaskProcessedMessage() throws Exception {
		RecordingNatsWakeClient natsClient = new RecordingNatsWakeClient();
		ProjectionTaskExecutorNatsProperties properties = new ProjectionTaskExecutorNatsProperties();
		ObjectMapper objectMapper = new ObjectMapper();
		NatsProjectionTaskProcessedPublisher publisher = new NatsProjectionTaskProcessedPublisher(
				natsClient,
				properties,
				objectMapper);
		UUID taskId = UUID.randomUUID();
		UUID potId = UUID.randomUUID();

		publisher.on(new ProjectionTaskProcessedEvent(
				taskId,
				PotId.of(potId),
				42,
				"PotUpdatedEvent",
				ProjectionTaskStatus.DONE));

		assertEquals(properties.getProjectionTasksProcessedSubject(), natsClient.subject);
		JsonNode payload = objectMapper.readTree(natsClient.payload);
		assertEquals("PROJECTION_TASK_PROCESSED", payload.get("signal").asText());
		assertEquals(potId.toString(), payload.get("potId").asText());
		assertEquals(taskId.toString(), payload.get("taskId").asText());
		assertEquals(42, payload.get("targetVersion").asLong());
		assertEquals("DONE", payload.get("status").asText());
	}

	private static final class RecordingNatsWakeClient implements ProjectionTaskExecutorNatsWakeClient {
		private String subject;
		private byte[] payload;

		@Override
		public void subscribe(String subject, Consumer<byte[]> handler) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void publish(String subject, byte[] payload) {
			this.subject = subject;
			this.payload = payload;
		}

		@Override
		public void close() {
		}
	}
}
