package com.kartaguez.pocoma.infra.tx.spring.fixture;

// Historical physical-transaction oracle, not a Runtime adapter.

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class FoundationTransactionConfiguration {

	@Bean
	@ConditionalOnMissingBean(FoundationRunner.class)
	FoundationRunner transactionRunner(PlatformTransactionManager transactionManager) {
		return new FoundationTransactionRunner(new TransactionTemplate(transactionManager));
	}
}
