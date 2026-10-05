package com.kartaguez.pocoma.orchestrator.poll.consumption.wait;

import java.time.Duration;

public interface ConsumptionWaiter {
	void await(Duration duration) throws InterruptedException;
	void signal();
}
