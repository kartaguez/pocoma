package com.kartaguez.pocoma.supra.worker.projection.taskexecutor.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pocoma.projection.nats")
public class ProjectionTaskExecutorNatsProperties {

	private boolean enabled = true;
	private String servers = "nats://localhost:4222";
	private String projectionTasksAvailableSubject = "pocoma.projection.tasks.available";
	private String projectionTasksProcessedSubject = "pocoma.projection.tasks.processed";

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getServers() {
		return servers;
	}

	public void setServers(String servers) {
		this.servers = servers;
	}

	public String getProjectionTasksAvailableSubject() {
		return projectionTasksAvailableSubject;
	}

	public void setProjectionTasksAvailableSubject(String projectionTasksAvailableSubject) {
		this.projectionTasksAvailableSubject = projectionTasksAvailableSubject;
	}

	public String getProjectionTasksProcessedSubject() {
		return projectionTasksProcessedSubject;
	}

	public void setProjectionTasksProcessedSubject(String projectionTasksProcessedSubject) {
		this.projectionTasksProcessedSubject = projectionTasksProcessedSubject;
	}
}
