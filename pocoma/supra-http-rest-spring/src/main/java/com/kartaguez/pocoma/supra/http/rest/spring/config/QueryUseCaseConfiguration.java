package com.kartaguez.pocoma.supra.http.rest.spring.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kartaguez.pocoma.domain.policy.ReadPotAuthorizationPolicy;
import com.kartaguez.pocoma.engine.port.in.query.usecase.GetExpenseUseCase;
import com.kartaguez.pocoma.engine.port.in.query.usecase.GetPotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.in.query.usecase.GetPotUseCase;
import com.kartaguez.pocoma.engine.port.in.query.usecase.ListPotExpensesUseCase;
import com.kartaguez.pocoma.engine.port.in.query.usecase.ListUserPotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.in.query.usecase.ListUserPotsUseCase;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseQueryPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotBalancesPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotQueryPort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.service.query.QueryUseCaseFactory;

@Configuration
public class QueryUseCaseConfiguration {

	@Bean
	ListUserPotsUseCase listUserPotsUseCase(PotQueryPort potQueryPort, TransactionRunner transactionRunner) {
		return QueryUseCaseFactory.listUserPotsUseCase(potQueryPort, transactionRunner);
	}

	@Bean
	GetPotUseCase getPotUseCase(PotQueryPort potQueryPort, TransactionRunner transactionRunner) {
		return QueryUseCaseFactory.getPotUseCase(
				potQueryPort,
				new ReadPotAuthorizationPolicy(),
				transactionRunner);
	}

	@Bean
	ListPotExpensesUseCase listPotExpensesUseCase(
			PotQueryPort potQueryPort,
			ExpenseQueryPort expenseQueryPort,
			TransactionRunner transactionRunner) {
		return QueryUseCaseFactory.listPotExpensesUseCase(
				potQueryPort,
				expenseQueryPort,
				new ReadPotAuthorizationPolicy(),
				transactionRunner);
	}

	@Bean
	GetExpenseUseCase getExpenseUseCase(
			PotQueryPort potQueryPort,
			ExpenseQueryPort expenseQueryPort,
			TransactionRunner transactionRunner) {
		return QueryUseCaseFactory.getExpenseUseCase(
				potQueryPort,
				expenseQueryPort,
				new ReadPotAuthorizationPolicy(),
				transactionRunner);
	}

	@Bean
	GetPotBalancesUseCase getPotBalancesUseCase(
			PotQueryPort potQueryPort,
			PotBalancesPort potBalancesPort,
			TransactionRunner transactionRunner) {
		return QueryUseCaseFactory.getPotBalancesUseCase(
				potQueryPort,
				potBalancesPort,
				new ReadPotAuthorizationPolicy(),
				transactionRunner);
	}

	@Bean
	ListUserPotBalancesUseCase listUserPotBalancesUseCase(
			PotQueryPort potQueryPort,
			PotBalancesPort potBalancesPort,
			TransactionRunner transactionRunner) {
		return QueryUseCaseFactory.listUserPotBalancesUseCase(potQueryPort, potBalancesPort, transactionRunner);
	}
}
