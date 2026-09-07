package com.kartaguez.pocoma.infra.read.persistence;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration(after = DataSourceTransactionManagerAutoConfiguration.class)
@ConditionalOnClass(JdbcOperations.class)
@ConditionalOnBean({ DataSource.class, PlatformTransactionManager.class })
public class ReadStoreAccessAutoConfiguration {

	@Bean("readStoreJdbcOperations")
	@ReadStore
	JdbcOperations readStoreJdbcOperations(DataSource dataSource) {
		return new JdbcTemplate(dataSource);
	}

	@Bean("readStoreTransactionOperations")
	@ReadStore
	TransactionOperations readStoreTransactionOperations(PlatformTransactionManager transactionManager) {
		return new TransactionTemplate(transactionManager);
	}
}
