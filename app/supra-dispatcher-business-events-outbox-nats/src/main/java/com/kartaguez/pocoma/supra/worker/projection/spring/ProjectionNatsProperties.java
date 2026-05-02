package com.kartaguez.pocoma.supra.worker.projection.spring;

import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pocoma.projection.nats")
public class ProjectionNatsProperties {

	private boolean enabled = true;
	private String servers = "nats://localhost:4222";
	private String businessEventsAvailableSubject = "pocoma.projection.business-events.available";
	private String projectionTasksAvailableSubject = "pocoma.projection.tasks.available";

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
		this.servers = requireText(servers, "servers must not be blank");
	}

	public String getBusinessEventsAvailableSubject() {
		return businessEventsAvailableSubject;
	}

	public void setBusinessEventsAvailableSubject(String businessEventsAvailableSubject) {
		this.businessEventsAvailableSubject = requireText(
				businessEventsAvailableSubject,
				"businessEventsAvailableSubject must not be blank");
	}

	public String getProjectionTasksAvailableSubject() {
		return projectionTasksAvailableSubject;
	}

	public void setProjectionTasksAvailableSubject(String projectionTasksAvailableSubject) {
		this.projectionTasksAvailableSubject = requireText(
				projectionTasksAvailableSubject,
				"projectionTasksAvailableSubject must not be blank");
	}

	private static String requireText(String value, String message) {
		Objects.requireNonNull(value, message);
		if (value.isBlank()) {
			throw new IllegalArgumentException(message);
		}
		return value;
	}
}
