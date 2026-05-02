package com.kartaguez.pocoma.supra.worker.projection.taskexecutor.spring;

import java.util.function.Consumer;

interface ProjectionTaskExecutorNatsWakeClient extends AutoCloseable {

	void subscribe(String subject, Consumer<byte[]> handler);

	void publish(String subject, byte[] payload);

	@Override
	void close();
}
