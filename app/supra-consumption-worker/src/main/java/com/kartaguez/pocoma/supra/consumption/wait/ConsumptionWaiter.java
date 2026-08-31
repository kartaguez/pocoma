package com.kartaguez.pocoma.supra.consumption.wait;

import java.time.Duration;

public interface ConsumptionWaiter {
	void await(Duration duration) throws InterruptedException;
	void signal();
}
