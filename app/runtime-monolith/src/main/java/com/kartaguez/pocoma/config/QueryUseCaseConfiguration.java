package com.kartaguez.pocoma.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kartaguez.pocoma.domain.pot.policy.ReadBalanceAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.ReadExpenseAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.ReadPotAuthorizationPolicy;
import com.kartaguez.pocoma.engine.port.in.query.usecase.GetExpenseUseCase;
import com.kartaguez.pocoma.engine.port.in.query.usecase.GetPotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.in.query.usecase.GetPotUseCase;
import com.kartaguez.pocoma.engine.port.in.query.usecase.ListPotExpensesUseCase;
import com.kartaguez.pocoma.engine.port.in.query.usecase.ListUserPotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.in.query.usecase.ListUserPotsUseCase;
import com.kartaguez.pocoma.engine.port.out.query.ExpenseQueryPort;
import com.kartaguez.pocoma.engine.port.out.query.PotBalancesQueryPort;
import com.kartaguez.pocoma.engine.port.out.query.PotQueryPort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.service.query.QueryUseCaseFactory;

@Configuration
public class QueryUseCaseConfiguration {

	@Bean
	ListUserPotsUseCase listUserPotsUseCase(PotQueryPort potQueryPort, TransactionRunner transactionRunner) {
		return QueryUseCaseFactory.listUserPotsUseCase(
				potQueryPort,
				new ReadPotAuthorizationPolicy(),
				transactionRunner);
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
				new ReadExpenseAuthorizationPolicy(),
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
				new ReadExpenseAuthorizationPolicy(),
				transactionRunner);
	}

	@Bean
	GetPotBalancesUseCase getPotBalancesUseCase(
			PotQueryPort potQueryPort,
			PotBalancesQueryPort potBalancesPort,
			TransactionRunner transactionRunner) {
		return QueryUseCaseFactory.getPotBalancesUseCase(
				potQueryPort,
				potBalancesPort,
				new ReadBalanceAuthorizationPolicy(),
				transactionRunner);
	}

	@Bean
	ListUserPotBalancesUseCase listUserPotBalancesUseCase(
			PotQueryPort potQueryPort,
			PotBalancesQueryPort potBalancesPort,
			TransactionRunner transactionRunner) {
		return QueryUseCaseFactory.listUserPotBalancesUseCase(
				potQueryPort,
				potBalancesPort,
				new ReadBalanceAuthorizationPolicy(),
				transactionRunner);
	}
}
