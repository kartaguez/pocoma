package com.kartaguez.pocoma.infra.tx.spring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

@Configuration
public class SpringTransactionRunnerConfiguration {

	@Bean
	@ConditionalOnBean(TransactionTemplate.class)
	@ConditionalOnMissingBean(TransactionRunner.class)
	TransactionRunner transactionRunner(TransactionTemplate transactionTemplate) {
		return new SpringTransactionRunner(transactionTemplate);
	}
}
