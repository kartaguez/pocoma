package com.kartaguez.pocoma.infra.read.persistence;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = FlywayAutoConfiguration.class)
@ConditionalOnClass(Flyway.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ReadStoreProperties.class)
public class ReadStoreMigrationAutoConfiguration {

	@Bean("readStoreMigrations")
	ReadStoreMigrator readStoreMigrator(DataSource dataSource, ReadStoreProperties properties) {
		return new ReadStoreMigrator(dataSource, properties);
	}
}
