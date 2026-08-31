package com.kartaguez.pocoma.supra.consumption;

import java.time.Duration;

public interface ConsumptionWorkerWaitStrategy {
	void await(Duration duration) throws InterruptedException;
	void signal();
}
