package com.kartaguez.pocoma.engine.service.command;

import java.util.Objects;

import com.kartaguez.pocoma.domain.policy.AddPotShareholdersAuthorizationPolicy;
import com.kartaguez.pocoma.domain.policy.CreateExpenseAuthorizationPolicy;
import com.kartaguez.pocoma.domain.policy.CreatePotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.policy.DeleteExpenseAuthorizationPolicy;
import com.kartaguez.pocoma.domain.policy.DeletePotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.policy.UpdateExpenseDetailsAuthorizationPolicy;
import com.kartaguez.pocoma.domain.policy.UpdateExpenseSharesAuthorizationPolicy;
import com.kartaguez.pocoma.domain.policy.UpdatePotDetailsAuthorizationPolicy;
import com.kartaguez.pocoma.domain.policy.UpdatePotShareholdersDetailsAuthorizationPolicy;
import com.kartaguez.pocoma.domain.policy.UpdatePotShareholdersWeightsAuthorizationPolicy;
import com.kartaguez.pocoma.engine.port.in.command.usecase.AddPotShareholdersUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.CreateExpenseUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.CreatePotUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.DeleteExpenseUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.DeletePotUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdateExpenseDetailsUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdateExpenseSharesUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdatePotDetailsUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdatePotShareholdersDetailsUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdatePotShareholdersWeightsUseCase;
import com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseHeaderPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseSharesPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotHeaderPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotShareholdersPort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.service.transaction.command.TransactionalAddPotShareholdersUseCase;
import com.kartaguez.pocoma.engine.service.transaction.command.TransactionalCreateExpenseUseCase;
import com.kartaguez.pocoma.engine.service.transaction.command.TransactionalCreatePotUseCase;
import com.kartaguez.pocoma.engine.service.transaction.command.TransactionalDeleteExpenseUseCase;
import com.kartaguez.pocoma.engine.service.transaction.command.TransactionalDeletePotUseCase;
import com.kartaguez.pocoma.engine.service.transaction.command.TransactionalUpdateExpenseDetailsUseCase;
import com.kartaguez.pocoma.engine.service.transaction.command.TransactionalUpdateExpenseSharesUseCase;
import com.kartaguez.pocoma.engine.service.transaction.command.TransactionalUpdatePotDetailsUseCase;
import com.kartaguez.pocoma.engine.service.transaction.command.TransactionalUpdatePotShareholdersDetailsUseCase;
import com.kartaguez.pocoma.engine.service.transaction.command.TransactionalUpdatePotShareholdersWeightsUseCase;

public final class CommandUseCaseFactory {

	private CommandUseCaseFactory() {
	}

	public static CreatePotUseCase createPotUseCase(
			PotGlobalVersionPort potGlobalVersionPort,
			PotHeaderPort potHeaderPort,
			EventPublisherPort eventPublisherPort,
			CreatePotAuthorizationPolicy authorizationPolicy,
			TransactionRunner transactionRunner) {
		return new TransactionalCreatePotUseCase(
				new CreatePotService(
						potGlobalVersionPort,
						potHeaderPort,
						eventPublisherPort,
						authorizationPolicy),
				requireTransactionRunner(transactionRunner));
	}

	public static CreateExpenseUseCase createExpenseUseCase(
			PotContextPort potContextPort,
			PotGlobalVersionPort potGlobalVersionPort,
			ExpenseHeaderPort expenseHeaderPort,
			ExpenseSharesPort expenseSharesPort,
			EventPublisherPort eventPublisherPort,
			CreateExpenseAuthorizationPolicy authorizationPolicy,
			TransactionRunner transactionRunner) {
		return new TransactionalCreateExpenseUseCase(
				new CreateExpenseService(
						potContextPort,
						potGlobalVersionPort,
						expenseHeaderPort,
						expenseSharesPort,
						eventPublisherPort,
						authorizationPolicy),
				requireTransactionRunner(transactionRunner));
	}

	public static AddPotShareholdersUseCase addPotShareholdersUseCase(
			PotContextPort potContextPort,
			PotShareholdersPort loadPotShareholdersPort,
			PotGlobalVersionPort potGlobalVersionPort,
			PotShareholdersPort replacePotShareholdersPort,
			EventPublisherPort eventPublisherPort,
			AddPotShareholdersAuthorizationPolicy authorizationPolicy,
			TransactionRunner transactionRunner) {
		return new TransactionalAddPotShareholdersUseCase(
				new AddPotShareholdersService(
						potContextPort,
						loadPotShareholdersPort,
						potGlobalVersionPort,
						replacePotShareholdersPort,
						eventPublisherPort,
						authorizationPolicy),
				requireTransactionRunner(transactionRunner));
	}

	public static DeletePotUseCase deletePotUseCase(
			PotContextPort potContextPort,
			PotHeaderPort loadPotHeaderPort,
			PotGlobalVersionPort potGlobalVersionPort,
			PotHeaderPort replacePotHeaderPort,
			EventPublisherPort eventPublisherPort,
			DeletePotAuthorizationPolicy authorizationPolicy,
			TransactionRunner transactionRunner) {
		return new TransactionalDeletePotUseCase(
				new DeletePotService(
						potContextPort,
						loadPotHeaderPort,
						potGlobalVersionPort,
						replacePotHeaderPort,
						eventPublisherPort,
						authorizationPolicy),
				requireTransactionRunner(transactionRunner));
	}

	public static DeleteExpenseUseCase deleteExpenseUseCase(
			ExpenseContextPort expenseContextPort,
			ExpenseHeaderPort loadExpenseHeaderPort,
			PotGlobalVersionPort potGlobalVersionPort,
			ExpenseHeaderPort replaceExpenseHeaderPort,
			EventPublisherPort eventPublisherPort,
			DeleteExpenseAuthorizationPolicy authorizationPolicy,
			TransactionRunner transactionRunner) {
		return new TransactionalDeleteExpenseUseCase(
				new DeleteExpenseService(
						expenseContextPort,
						loadExpenseHeaderPort,
						potGlobalVersionPort,
						replaceExpenseHeaderPort,
						eventPublisherPort,
						authorizationPolicy),
				requireTransactionRunner(transactionRunner));
	}

	public static UpdatePotDetailsUseCase updatePotDetailsUseCase(
			PotContextPort potContextPort,
			PotHeaderPort loadPotHeaderPort,
			PotGlobalVersionPort potGlobalVersionPort,
			PotHeaderPort replacePotHeaderPort,
			EventPublisherPort eventPublisherPort,
			UpdatePotDetailsAuthorizationPolicy authorizationPolicy,
			TransactionRunner transactionRunner) {
		return new TransactionalUpdatePotDetailsUseCase(
				new UpdatePotDetailsService(
						potContextPort,
						loadPotHeaderPort,
						potGlobalVersionPort,
						replacePotHeaderPort,
						eventPublisherPort,
						authorizationPolicy),
				requireTransactionRunner(transactionRunner));
	}

	public static UpdateExpenseDetailsUseCase updateExpenseDetailsUseCase(
			ExpenseContextPort expenseContextPort,
			ExpenseHeaderPort loadExpenseHeaderPort,
			PotGlobalVersionPort potGlobalVersionPort,
			ExpenseHeaderPort replaceExpenseHeaderPort,
			EventPublisherPort eventPublisherPort,
			UpdateExpenseDetailsAuthorizationPolicy authorizationPolicy,
			TransactionRunner transactionRunner) {
		return new TransactionalUpdateExpenseDetailsUseCase(
				new UpdateExpenseDetailsService(
						expenseContextPort,
						loadExpenseHeaderPort,
						potGlobalVersionPort,
						replaceExpenseHeaderPort,
						eventPublisherPort,
						authorizationPolicy),
				requireTransactionRunner(transactionRunner));
	}

	public static UpdateExpenseSharesUseCase updateExpenseSharesUseCase(
			ExpenseContextPort expenseContextPort,
			ExpenseSharesPort loadExpenseSharesPort,
			PotGlobalVersionPort potGlobalVersionPort,
			ExpenseSharesPort replaceExpenseSharesPort,
			EventPublisherPort eventPublisherPort,
			UpdateExpenseSharesAuthorizationPolicy authorizationPolicy,
			TransactionRunner transactionRunner) {
		return new TransactionalUpdateExpenseSharesUseCase(
				new UpdateExpenseSharesService(
						expenseContextPort,
						loadExpenseSharesPort,
						potGlobalVersionPort,
						replaceExpenseSharesPort,
						eventPublisherPort,
						authorizationPolicy),
				requireTransactionRunner(transactionRunner));
	}

	public static UpdatePotShareholdersDetailsUseCase updatePotShareholdersDetailsUseCase(
			PotContextPort potContextPort,
			PotShareholdersPort loadPotShareholdersPort,
			PotGlobalVersionPort potGlobalVersionPort,
			PotShareholdersPort replacePotShareholdersPort,
			EventPublisherPort eventPublisherPort,
			UpdatePotShareholdersDetailsAuthorizationPolicy authorizationPolicy,
			TransactionRunner transactionRunner) {
		return new TransactionalUpdatePotShareholdersDetailsUseCase(
				new UpdatePotShareholdersDetailsService(
						potContextPort,
						loadPotShareholdersPort,
						potGlobalVersionPort,
						replacePotShareholdersPort,
						eventPublisherPort,
						authorizationPolicy),
				requireTransactionRunner(transactionRunner));
	}

	public static UpdatePotShareholdersWeightsUseCase updatePotShareholdersWeightsUseCase(
			PotContextPort potContextPort,
			PotShareholdersPort loadPotShareholdersPort,
			PotGlobalVersionPort potGlobalVersionPort,
			PotShareholdersPort replacePotShareholdersPort,
			EventPublisherPort eventPublisherPort,
			UpdatePotShareholdersWeightsAuthorizationPolicy authorizationPolicy,
			TransactionRunner transactionRunner) {
		return new TransactionalUpdatePotShareholdersWeightsUseCase(
				new UpdatePotShareholdersWeightsService(
						potContextPort,
						loadPotShareholdersPort,
						potGlobalVersionPort,
						replacePotShareholdersPort,
						eventPublisherPort,
						authorizationPolicy),
				requireTransactionRunner(transactionRunner));
	}

	private static TransactionRunner requireTransactionRunner(TransactionRunner transactionRunner) {
		return Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
	}
}
