package com.kartaguez.pocoma.engine.service.query;

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
import com.kartaguez.pocoma.engine.service.transaction.query.TransactionalGetExpenseUseCase;
import com.kartaguez.pocoma.engine.service.transaction.query.TransactionalGetPotBalancesUseCase;
import com.kartaguez.pocoma.engine.service.transaction.query.TransactionalGetPotUseCase;
import com.kartaguez.pocoma.engine.service.transaction.query.TransactionalListPotExpensesUseCase;
import com.kartaguez.pocoma.engine.service.transaction.query.TransactionalListUserPotBalancesUseCase;
import com.kartaguez.pocoma.engine.service.transaction.query.TransactionalListUserPotsUseCase;

public final class QueryUseCaseFactory {

	private QueryUseCaseFactory() {
	}

	public static ListUserPotsUseCase listUserPotsUseCase(
			PotQueryPort potQueryPort,
			ReadPotAuthorizationPolicy readPotAuthorizationPolicy,
			TransactionRunner transactionRunner) {
		return new TransactionalListUserPotsUseCase(
				new ListUserPotsService(potQueryPort, readPotAuthorizationPolicy),
				transactionRunner);
	}

	public static GetPotUseCase getPotUseCase(
			PotQueryPort potQueryPort,
			ReadPotAuthorizationPolicy readPotAuthorizationPolicy,
			TransactionRunner transactionRunner) {
		return new TransactionalGetPotUseCase(
				new GetPotService(potQueryPort, readPotAuthorizationPolicy),
				transactionRunner);
	}

	public static ListPotExpensesUseCase listPotExpensesUseCase(
			PotQueryPort potQueryPort,
			ExpenseQueryPort expenseQueryPort,
			ReadExpenseAuthorizationPolicy readExpenseAuthorizationPolicy,
			TransactionRunner transactionRunner) {
		return new TransactionalListPotExpensesUseCase(
				new ListPotExpensesService(potQueryPort, expenseQueryPort, readExpenseAuthorizationPolicy),
				transactionRunner);
	}

	public static GetExpenseUseCase getExpenseUseCase(
			PotQueryPort potQueryPort,
			ExpenseQueryPort expenseQueryPort,
			ReadExpenseAuthorizationPolicy readExpenseAuthorizationPolicy,
			TransactionRunner transactionRunner) {
		return new TransactionalGetExpenseUseCase(
				new GetExpenseService(potQueryPort, expenseQueryPort, readExpenseAuthorizationPolicy),
				transactionRunner);
	}

	public static GetPotBalancesUseCase getPotBalancesUseCase(
			PotQueryPort potQueryPort,
			PotBalancesQueryPort potBalancesPort,
			ReadBalanceAuthorizationPolicy readBalanceAuthorizationPolicy,
			TransactionRunner transactionRunner) {
		return new TransactionalGetPotBalancesUseCase(
				new GetPotBalancesService(potQueryPort, potBalancesPort, readBalanceAuthorizationPolicy),
				transactionRunner);
	}

	public static ListUserPotBalancesUseCase listUserPotBalancesUseCase(
			PotQueryPort potQueryPort,
			PotBalancesQueryPort potBalancesPort,
			ReadBalanceAuthorizationPolicy readBalanceAuthorizationPolicy,
			TransactionRunner transactionRunner) {
		return new TransactionalListUserPotBalancesUseCase(
				new ListUserPotBalancesService(potQueryPort, potBalancesPort, readBalanceAuthorizationPolicy),
				transactionRunner);
	}
}
