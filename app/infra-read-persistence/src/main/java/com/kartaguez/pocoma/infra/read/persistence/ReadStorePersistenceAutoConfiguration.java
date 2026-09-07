package com.kartaguez.pocoma.infra.read.persistence;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration(after = { FlywayAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class })
@ConditionalOnClass({ Flyway.class, JdbcOperations.class })
@ConditionalOnBean({ DataSource.class, PlatformTransactionManager.class, Flyway.class })
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ReadStoreProperties.class)
public class ReadStorePersistenceAutoConfiguration {

	@Bean("readStoreMigrations")
	@DependsOn("flywayInitializer")
	ReadStoreMigrator readStoreMigrator(DataSource dataSource, ReadStoreProperties properties) {
		return new ReadStoreMigrator(dataSource, properties);
	}

	@Bean("readStoreJdbcOperations")
	@ReadStore
	@DependsOn("readStoreMigrations")
	JdbcOperations readStoreJdbcOperations(DataSource dataSource) {
		return new JdbcTemplate(dataSource);
	}

	@Bean("readStoreTransactionOperations")
	@ReadStore
	@DependsOn("readStoreMigrations")
	TransactionOperations readStoreTransactionOperations(PlatformTransactionManager transactionManager) {
		return new TransactionTemplate(transactionManager);
	}
}
