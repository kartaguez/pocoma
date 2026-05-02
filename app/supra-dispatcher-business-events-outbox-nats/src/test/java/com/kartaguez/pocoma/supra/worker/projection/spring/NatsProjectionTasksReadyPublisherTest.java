package com.kartaguez.pocoma.supra.worker.projection.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.event.projection.ProjectionTasksReadyEvent;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWakeSignals;

class NatsProjectionTasksReadyPublisherTest {

	@Test
	void publishesProjectionTasksReadyWakeSignalToNats() throws Exception {
		RecordingNatsWakeClient natsClient = new RecordingNatsWakeClient();
		ProjectionNatsProperties properties = new ProjectionNatsProperties();
		ObjectMapper objectMapper = new ObjectMapper();
		NatsProjectionTasksReadyPublisher publisher =
				new NatsProjectionTasksReadyPublisher(natsClient, properties, objectMapper);
		PotId potId = PotId.of(UUID.randomUUID());

		publisher.on(new ProjectionTasksReadyEvent(UUID.randomUUID(), potId, 7, "ExpenseCreatedEvent"));

		ProjectionWakePayload payload = objectMapper.readValue(natsClient.payload, ProjectionWakePayload.class);
		assertEquals(properties.getProjectionTasksAvailableSubject(), natsClient.subject);
		assertEquals(ProjectionWakeSignals.PROJECTION_TASKS_AVAILABLE, payload.signal());
		assertEquals(potId.value().toString(), payload.potId());
	}

	private static final class RecordingNatsWakeClient implements NatsWakeClient {
		private String subject;
		private byte[] payload;

		@Override
		public void subscribe(String subject, Consumer<byte[]> handler) {
		}

		@Override
		public void publish(String subject, byte[] payload) {
			this.subject = subject;
			this.payload = new String(payload, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
		}

		@Override
		public void close() {
		}
	}
}
