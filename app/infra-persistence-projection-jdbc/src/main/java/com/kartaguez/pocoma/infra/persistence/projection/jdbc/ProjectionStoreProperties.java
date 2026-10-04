package com.kartaguez.pocoma.infra.persistence.projection.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pocoma.read-store")
public final class ProjectionStoreProperties {
	private String schema = "pocoma_read";

	public String getSchema() {
		return schema;
	}

	public void setSchema(String schema) {
		this.schema = schema;
	}
}
