package com.kartaguez.pocoma.supra.worker.projection.taskexecutor.spring;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.SmartLifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWakeSignals;

final class NatsProjectionTasksWakeSubscriber implements SmartLifecycle {

	private static final System.Logger LOGGER = System.getLogger(NatsProjectionTasksWakeSubscriber.class.getName());

	private final ProjectionTaskExecutorNatsWakeClient natsClient;
	private final ProjectionTaskExecutorNatsProperties properties;
	private final ObjectMapper objectMapper;
	private final WorkWakeBus<String, PotId> wakeBus;
	private volatile boolean running;

	NatsProjectionTasksWakeSubscriber(
			ProjectionTaskExecutorNatsWakeClient natsClient,
			ProjectionTaskExecutorNatsProperties properties,
			ObjectMapper objectMapper,
			WorkWakeBus<String, PotId> wakeBus) {
		this.natsClient = Objects.requireNonNull(natsClient, "natsClient must not be null");
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
		this.wakeBus = Objects.requireNonNull(wakeBus, "wakeBus must not be null");
	}

	@Override
	public void start() {
		if (running) {
			return;
		}
		natsClient.subscribe(properties.getProjectionTasksAvailableSubject(), this::onMessage);
		running = true;
	}

	@Override
	public void stop() {
		if (!running) {
			return;
		}
		natsClient.close();
		running = false;
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	@Override
	public int getPhase() {
		return Integer.MAX_VALUE - 100;
	}

	private void onMessage(byte[] payload) {
		try {
			ProjectionTaskWakePayload wakePayload = objectMapper.readValue(payload, ProjectionTaskWakePayload.class);
			if (!ProjectionWakeSignals.PROJECTION_TASKS_AVAILABLE.equals(wakePayload.signal())) {
				LOGGER.log(System.Logger.Level.DEBUG, "Ignoring projection task wake signal {0}", wakePayload.signal());
				return;
			}
			wakeBus.publish(ProjectionWakeSignals.PROJECTION_TASKS_AVAILABLE, PotId.of(UUID.fromString(wakePayload.potId())));
		} catch (IOException | IllegalArgumentException e) {
			LOGGER.log(System.Logger.Level.WARNING, "Ignoring invalid projection task wake signal", e);
		}
	}
}
