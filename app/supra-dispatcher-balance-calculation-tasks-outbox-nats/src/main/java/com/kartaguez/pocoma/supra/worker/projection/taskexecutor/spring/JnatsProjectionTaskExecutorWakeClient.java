package com.kartaguez.pocoma.supra.worker.projection.taskexecutor.spring;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;
import io.nats.client.Options;

final class JnatsProjectionTaskExecutorWakeClient implements ProjectionTaskExecutorNatsWakeClient {

	private static final Duration FLUSH_TIMEOUT = Duration.ofSeconds(1);

	private final Connection connection;
	private final List<Dispatcher> dispatchers = new CopyOnWriteArrayList<>();

	private JnatsProjectionTaskExecutorWakeClient(Connection connection) {
		this.connection = connection;
	}

	static JnatsProjectionTaskExecutorWakeClient connect(ProjectionTaskExecutorNatsProperties properties)
			throws IOException, InterruptedException {
		Options options = new Options.Builder()
				.server(properties.getServers())
				.connectionName("pocoma-projection-task-executor")
				.build();
		return new JnatsProjectionTaskExecutorWakeClient(Nats.connect(options));
	}

	@Override
	public void subscribe(String subject, Consumer<byte[]> handler) {
		Dispatcher dispatcher = connection.createDispatcher(message -> handler.accept(message.getData()));
		dispatcher.subscribe(subject);
		dispatchers.add(dispatcher);
	}

	@Override
	public void publish(String subject, byte[] payload) {
		connection.publish(subject, payload);
		try {
			connection.flush(FLUSH_TIMEOUT);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while flushing NATS wake signal", e);
		} catch (TimeoutException e) {
			throw new IllegalStateException("Timed out while flushing NATS wake signal", e);
		}
	}

	@Override
	public void close() {
		for (Dispatcher dispatcher : dispatchers) {
			connection.closeDispatcher(dispatcher);
		}
		try {
			connection.close();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
