package com.kartaguez.pocoma.supra.worker.projection.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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

class NatsBusinessEventsWakeSubscriberTest {

	@Test
	void wakesLocalWaiterWhenBusinessEventsNatsSignalArrives() {
		RecordingNatsWakeClient natsClient = new RecordingNatsWakeClient();
		ProjectionNatsProperties properties = new ProjectionNatsProperties();
		RecordingWakeBus wakeBus = new RecordingWakeBus();
		NatsBusinessEventsWakeSubscriber subscriber = new NatsBusinessEventsWakeSubscriber(
				natsClient,
				properties,
				new ObjectMapper(),
				wakeBus);
		PotId potId = PotId.of(UUID.randomUUID());

		subscriber.start();
		natsClient.deliver("""
				{"signal":"BUSINESS_EVENTS_AVAILABLE","potId":"%s","occurredAt":"2026-05-02T00:00:00Z"}
				""".formatted(potId.value()).getBytes(StandardCharsets.UTF_8));

		assertEquals(properties.getBusinessEventsAvailableSubject(), natsClient.subject);
		assertEquals(List.of(ProjectionWakeSignals.BUSINESS_EVENTS_AVAILABLE), wakeBus.signals);
		assertEquals(List.of(potId), wakeBus.potIds);
	}

	private static final class RecordingNatsWakeClient implements NatsWakeClient {
		private String subject;
		private Consumer<byte[]> handler;

		@Override
		public void subscribe(String subject, Consumer<byte[]> handler) {
			this.subject = subject;
			this.handler = handler;
		}

		@Override
		public void publish(String subject, byte[] payload) {
		}

		@Override
		public void close() {
		}

		private void deliver(byte[] payload) {
			handler.accept(payload);
		}
	}

	private static final class RecordingWakeBus implements WorkWakeBus<String, PotId> {
		private final List<String> signals = new ArrayList<>();
		private final List<PotId> potIds = new ArrayList<>();

		@Override
		public void publish(WorkWakeEvent<String, PotId> event) {
			signals.add(event.signal());
			potIds.add(event.key());
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
