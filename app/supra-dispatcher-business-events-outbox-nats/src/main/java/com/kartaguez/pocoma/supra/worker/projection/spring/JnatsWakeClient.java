package com.kartaguez.pocoma.supra.worker.projection.spring;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;

final class JnatsWakeClient implements NatsWakeClient {

	private final Connection connection;
	private final List<Dispatcher> dispatchers = new ArrayList<>();

	JnatsWakeClient(Connection connection) {
		this.connection = Objects.requireNonNull(connection, "connection must not be null");
	}

	static JnatsWakeClient connect(ProjectionNatsProperties properties) throws IOException, InterruptedException {
		Objects.requireNonNull(properties, "properties must not be null");
		return new JnatsWakeClient(Nats.connect(properties.getServers()));
	}

	@Override
	public void subscribe(String subject, Consumer<byte[]> handler) {
		Objects.requireNonNull(subject, "subject must not be null");
		Objects.requireNonNull(handler, "handler must not be null");
		Dispatcher dispatcher = connection.createDispatcher(message -> handler.accept(message.getData()));
		dispatcher.subscribe(subject);
		dispatchers.add(dispatcher);
	}

	@Override
	public void publish(String subject, byte[] payload) {
		Objects.requireNonNull(subject, "subject must not be null");
		Objects.requireNonNull(payload, "payload must not be null");
		connection.publish(subject, payload);
		try {
			connection.flush(Durations.DEFAULT_FLUSH_TIMEOUT);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while flushing NATS wake event", exception);
		}
		catch (TimeoutException exception) {
			throw new IllegalStateException("Timed out while flushing NATS wake event", exception);
		}
	}

	@Override
	public void close() {
		for (Dispatcher dispatcher : dispatchers) {
			connection.closeDispatcher(dispatcher);
		}
		dispatchers.clear();
		try {
			connection.close();
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}

	private static final class Durations {
		private static final java.time.Duration DEFAULT_FLUSH_TIMEOUT = java.time.Duration.ofSeconds(1);
	}
}
