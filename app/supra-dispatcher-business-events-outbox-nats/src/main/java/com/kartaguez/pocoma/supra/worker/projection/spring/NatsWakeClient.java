package com.kartaguez.pocoma.supra.worker.projection.spring;

import java.util.function.Consumer;

interface NatsWakeClient extends AutoCloseable {

	void subscribe(String subject, Consumer<byte[]> handler);

	void publish(String subject, byte[] payload);

	@Override
	void close();
}
