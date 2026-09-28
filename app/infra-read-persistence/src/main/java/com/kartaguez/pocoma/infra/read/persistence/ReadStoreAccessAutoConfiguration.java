package com.kartaguez.pocoma.infra.read.persistence;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.kartaguez.pocoma.engine.read.projection.ProjectionMetadataPort;
import com.kartaguez.pocoma.engine.read.projection.LatestKnownVersionPersistencePort;
import com.kartaguez.pocoma.engine.read.projection.PotUserIndexReader;

@AutoConfiguration(after = DataSourceTransactionManagerAutoConfiguration.class)
@ConditionalOnClass(JdbcOperations.class)
@ConditionalOnBean({ DataSource.class, PlatformTransactionManager.class })
@EnableConfigurationProperties(ReadStoreProperties.class)
public class ReadStoreAccessAutoConfiguration {

	@Bean("readStoreJdbcOperations")
	@ReadStore
	JdbcTemplate readStoreJdbcOperations(DataSource dataSource) {
		return new JdbcTemplate(dataSource);
	}

	@Bean
	ProjectionMetadataPort projectionMetadataPort(@ReadStore JdbcOperations jdbc,
			ReadStoreProperties properties) {
		return new JdbcProjectionMetadataAdapter(jdbc, properties.getSchema());
	}

	@Bean
	LatestKnownVersionPersistencePort latestKnownVersionPersistencePort(
			@ReadStore JdbcOperations jdbc, ReadStoreProperties properties) {
		return new JdbcLatestKnownVersionAdapter(jdbc, properties.getSchema());
	}

	@Bean
	PotUserIndexReader potUserIndexReader(
			@ReadStore JdbcOperations jdbc, ReadStoreProperties properties) {
		return new JdbcPotUserIndexReader(jdbc, properties.getSchema());
	}

}
