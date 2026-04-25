package com.kartaguez.pocoma.supra.worker.projection.spring;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.kartaguez.pocoma.supra.worker.projection.core.ProjectionWorkerSettings;

@ConfigurationProperties(prefix = "pocoma.projection.worker")
public class ProjectionWorkerProperties {

	private boolean enabled = true;

	private int threadCount = ProjectionWorkerSettings.DEFAULT_THREAD_COUNT;

	private int queueCapacity = ProjectionWorkerSettings.DEFAULT_QUEUE_CAPACITY;

	private int maxRetries = ProjectionWorkerSettings.DEFAULT_MAX_RETRIES;

	private Duration initialBackoff = ProjectionWorkerSettings.DEFAULT_INITIAL_BACKOFF;

	private Duration maxBackoff = ProjectionWorkerSettings.DEFAULT_MAX_BACKOFF;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public int getThreadCount() {
		return threadCount;
	}

	public void setThreadCount(int threadCount) {
		this.threadCount = threadCount;
	}

	public int getQueueCapacity() {
		return queueCapacity;
	}

	public void setQueueCapacity(int queueCapacity) {
		this.queueCapacity = queueCapacity;
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

	ProjectionWorkerSettings toSettings() {
		return new ProjectionWorkerSettings(threadCount, queueCapacity, maxRetries, initialBackoff, maxBackoff);
	}
}
