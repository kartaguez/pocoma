package com.kartaguez.pocoma.supra.worker.balancecalculation.spring;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.kartaguez.pocoma.supra.dispatcher.projection.shared.taskexecutor.ProjectionTaskExecutorSettings;

@ConfigurationProperties(prefix = "pocoma.projection.worker")
public class BalanceCalculationWorkerProperties {

	private int threadCount = ProjectionTaskExecutorSettings.DEFAULT_THREAD_COUNT;
	private int maxRetries = ProjectionTaskExecutorSettings.DEFAULT_MAX_RETRIES;
	private Duration initialBackoff = ProjectionTaskExecutorSettings.DEFAULT_INITIAL_BACKOFF;
	private Duration maxBackoff = ProjectionTaskExecutorSettings.DEFAULT_MAX_BACKOFF;

	public int getThreadCount() {
		return threadCount;
	}

	public void setThreadCount(int threadCount) {
		this.threadCount = threadCount;
	}

	public int getMaxRetries() {
		return maxRetries;
	}

	public void setMaxRetries(int maxRetries) {
		this.maxRetries = maxRetries;
	}

	public Duration getInitialBackoff() {
		return initialBackoff;
	}

	public void setInitialBackoff(Duration initialBackoff) {
		this.initialBackoff = initialBackoff;
	}

	public Duration getMaxBackoff() {
		return maxBackoff;
	}

	public void setMaxBackoff(Duration maxBackoff) {
		this.maxBackoff = maxBackoff;
	}

	ProjectionTaskExecutorSettings toSettings() {
		return new ProjectionTaskExecutorSettings(
				threadCount,
				ProjectionTaskExecutorSettings.DEFAULT_QUEUE_CAPACITY,
				maxRetries,
				initialBackoff,
				maxBackoff);
	}
}
