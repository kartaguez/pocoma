package com.kartaguez.pocoma.infra.read.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pocoma.read-store")
public class ReadStoreProperties {

	private String schema = "pocoma_read";
	private String migrationLocation = "classpath:db/read-store/migration";
	private String historyTable = "flyway_schema_history";

	public String getSchema() {
		return schema;
	}

	public void setSchema(String schema) {
		this.schema = schema;
	}

	public String getMigrationLocation() {
		return migrationLocation;
	}

	public void setMigrationLocation(String migrationLocation) {
		this.migrationLocation = migrationLocation;
	}

	public String getHistoryTable() {
		return historyTable;
	}

	public void setHistoryTable(String historyTable) {
		this.historyTable = historyTable;
	}
}
