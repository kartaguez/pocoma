package com.kartaguez.pocoma.infra.event.publisher.spring;

import java.util.Objects;

import com.kartaguez.pocoma.engine.port.out.persistence.BusinessEventOutboxAppendPort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public class OutboxThenSpringEventPublisherAdapter {

	private final BusinessEventOutboxAppendPort outboxPort;
	private final SpringApplicationEventPublisher springApplicationEventPublisher;
	private final TransactionRunner transactionRunner;

	public OutboxThenSpringEventPublisherAdapter(
			BusinessEventOutboxAppendPort outboxPort,
			SpringApplicationEventPublisher springApplicationEventPublisher,
			TransactionRunner transactionRunner) {
		this.outboxPort = Objects.requireNonNull(outboxPort, "outboxPort must not be null");
		this.springApplicationEventPublisher = Objects.requireNonNull(
				springApplicationEventPublisher,
				"springApplicationEventPublisher must not be null");
		this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	public void publish(Object event) {
		Objects.requireNonNull(event, "event must not be null");
		outboxPort.append(event);
		transactionRunner.runAfterCommit(() -> springApplicationEventPublisher.publish(event));
	}
}
