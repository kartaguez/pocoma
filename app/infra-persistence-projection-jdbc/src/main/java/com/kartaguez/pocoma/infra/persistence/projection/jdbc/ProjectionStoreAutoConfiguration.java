package com.kartaguez.pocoma.infra.persistence.projection.jdbc;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration(after = DataSourceTransactionManagerAutoConfiguration.class)
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnBean({ DataSource.class, PlatformTransactionManager.class })
@EnableConfigurationProperties(ProjectionStoreProperties.class)
public class ProjectionStoreAutoConfiguration {

	@Bean
	JdbcProjectionStoreAdapter projectionStoreAdapter(DataSource dataSource,
			PlatformTransactionManager transactionManager, ProjectionStoreProperties properties) {
		return new JdbcProjectionStoreAdapter(new JdbcTemplate(dataSource),
				new TransactionTemplate(transactionManager), properties.getSchema());
	}
}
