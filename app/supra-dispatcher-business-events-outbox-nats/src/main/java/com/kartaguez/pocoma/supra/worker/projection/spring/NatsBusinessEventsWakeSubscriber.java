package com.kartaguez.pocoma.supra.worker.projection.spring;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.context.SmartLifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWakeSignals;

public class NatsBusinessEventsWakeSubscriber implements SmartLifecycle {

	private static final System.Logger LOGGER = System.getLogger(NatsBusinessEventsWakeSubscriber.class.getName());

	private final NatsWakeClient natsClient;
	private final ProjectionNatsProperties properties;
	private final ObjectMapper objectMapper;
	private final WorkWakeBus<String, PotId> wakeBus;
	private final AtomicBoolean running = new AtomicBoolean(false);

	NatsBusinessEventsWakeSubscriber(
			NatsWakeClient natsClient,
			ProjectionNatsProperties properties,
			ObjectMapper objectMapper,
			WorkWakeBus<String, PotId> wakeBus) {
		this.natsClient = Objects.requireNonNull(natsClient, "natsClient must not be null");
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
		this.wakeBus = Objects.requireNonNull(wakeBus, "wakeBus must not be null");
	}

	@Override
	public void start() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		natsClient.subscribe(properties.getBusinessEventsAvailableSubject(), this::onMessage);
	}

	@Override
	public void stop() {
		if (!running.compareAndSet(true, false)) {
			return;
		}
		natsClient.close();
	}

	@Override
	public boolean isRunning() {
		return running.get();
	}

	@Override
	public int getPhase() {
		return Integer.MAX_VALUE - 100;
	}

	void onMessage(byte[] payload) {
		try {
			ProjectionWakePayload wakePayload = objectMapper.readValue(payload, ProjectionWakePayload.class);
			if (!ProjectionWakeSignals.BUSINESS_EVENTS_AVAILABLE.equals(wakePayload.signal())) {
				return;
			}
			wakeBus.publish(wakePayload.signal(), PotId.of(UUID.fromString(wakePayload.potId())));
		}
		catch (IOException | IllegalArgumentException exception) {
			LOGGER.log(System.Logger.Level.WARNING, "Ignoring invalid NATS projection wake payload", exception);
		}
	}
}
