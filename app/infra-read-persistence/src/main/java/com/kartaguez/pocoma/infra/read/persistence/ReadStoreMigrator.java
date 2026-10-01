package com.kartaguez.pocoma.infra.read.persistence;

import java.util.Objects;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;

final class ReadStoreMigrator implements InitializingBean {

	private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	private final DataSource dataSource;
	private final ReadStoreProperties properties;

	ReadStoreMigrator(DataSource dataSource, ReadStoreProperties properties) {
		this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
	}

	@Override
	public void afterPropertiesSet() {
		String schema = requireSqlIdentifier(properties.getSchema(), "schema");
		String historyTable = requireSqlIdentifier(properties.getHistoryTable(), "historyTable");
		String migrationLocation = requireNonBlank(properties.getMigrationLocation(), "migrationLocation");

		Flyway.configure()
				.dataSource(dataSource)
				.defaultSchema(schema)
				.schemas(schema)
				.createSchemas(true)
				.table(historyTable)
				.locations(migrationLocation)
				.load()
				.migrate();
	}

	private static String requireSqlIdentifier(String value, String name) {
		String candidate = requireNonBlank(value, name);
		if (!SQL_IDENTIFIER.matcher(candidate).matches()) {
			throw new IllegalArgumentException(name + " must be a simple SQL identifier");
		}
		return candidate;
	}

	private static String requireNonBlank(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
