package com.kartaguez.pocoma.supra.worker.projection.taskexecutor.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeEvent;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeSubscription;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWakeSignals;

class NatsProjectionTasksWakeSubscriberTest {

	@Test
	void wakesLocalBusWhenProjectionTasksAvailableMessageIsReceived() throws Exception {
		FakeNatsWakeClient natsClient = new FakeNatsWakeClient();
		ProjectionTaskExecutorNatsProperties properties = new ProjectionTaskExecutorNatsProperties();
		RecordingWakeBus wakeBus = new RecordingWakeBus();
		NatsProjectionTasksWakeSubscriber subscriber = new NatsProjectionTasksWakeSubscriber(
				natsClient,
				properties,
				new ObjectMapper(),
				wakeBus);

		subscriber.start();
		UUID potId = UUID.randomUUID();
		natsClient.deliver("""
				{"signal":"PROJECTION_TASKS_AVAILABLE","potId":"%s","occurredAt":"2026-05-03T00:00:00Z"}
				""".formatted(potId));

		assertEquals(properties.getProjectionTasksAvailableSubject(), natsClient.subject);
		assertEquals(ProjectionWakeSignals.PROJECTION_TASKS_AVAILABLE, wakeBus.signal);
		assertEquals(PotId.of(potId), wakeBus.key);
	}

	private static final class FakeNatsWakeClient implements ProjectionTaskExecutorNatsWakeClient {
		private String subject;
		private Consumer<byte[]> handler;

		@Override
		public void subscribe(String subject, Consumer<byte[]> handler) {
			this.subject = subject;
			this.handler = handler;
		}

		@Override
		public void publish(String subject, byte[] payload) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void close() {
		}

		private void deliver(String json) {
			handler.accept(json.getBytes());
		}
	}

	private static final class RecordingWakeBus implements WorkWakeBus<String, PotId> {
		private String signal;
		private PotId key;

		@Override
		public void publish(WorkWakeEvent<String, PotId> event) {
			this.signal = event.signal();
			this.key = event.key();
		}

		@Override
		public WorkWakeSubscription subscribe(
				Set<String> signals,
				Predicate<PotId> keyPredicate,
				Runnable listener) {
			return () -> {
			};
		}
	}
}
