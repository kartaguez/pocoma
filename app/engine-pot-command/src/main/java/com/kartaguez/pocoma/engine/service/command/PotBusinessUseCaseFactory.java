package com.kartaguez.pocoma.engine.service.command;

import com.kartaguez.pocoma.domain.pot.policy.CreatePotAuthorizationPolicy;
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

/** Builds raw Pot business ports with invocation-local collaborators. */
final class PotBusinessUseCaseFactory {

	private PotBusinessUseCaseFactory() {
	}

	static CreatePotUseCase createPot(
			PotGlobalVersionPort versions,
			PotHeaderPort headers,
			EventPublisherPort events,
			CreatePotAuthorizationPolicy policy) {
		return new CreatePotService(versions, headers, events, policy);
	}

	static CreateExpenseUseCase createExpense(
			PotContextPort contexts,
			PotGlobalVersionPort versions,
			ExpenseHeaderPort headers,
			ExpenseSharesPort shares,
			EventPublisherPort events,
			PotAuthorizationGuard policy) {
		return new CreateExpenseService(contexts, versions, headers, shares, events, policy);
	}

	static AddPotShareholdersUseCase addShareholders(
			PotContextPort contexts,
			PotShareholdersPort shareholders,
			PotGlobalVersionPort versions,
			EventPublisherPort events,
			PotAuthorizationGuard policy) {
		return new AddPotShareholdersService(contexts, shareholders, versions, shareholders, events, policy);
	}

	static DeletePotUseCase deletePot(PotContextPort contexts, PotHeaderPort headers,
			PotGlobalVersionPort versions, EventPublisherPort events, PotAuthorizationGuard policy) {
		return new DeletePotService(contexts, headers, versions, headers, events, policy);
	}

	static DeleteExpenseUseCase deleteExpense(ExpenseContextPort contexts, ExpenseHeaderPort headers,
			PotGlobalVersionPort versions, EventPublisherPort events, PotAuthorizationGuard policy) {
		return new DeleteExpenseService(contexts, headers, versions, headers, events, policy);
	}

	static UpdatePotDetailsUseCase updatePotDetails(PotContextPort contexts, PotHeaderPort headers,
			PotGlobalVersionPort versions, EventPublisherPort events, PotAuthorizationGuard policy) {
		return new UpdatePotDetailsService(contexts, headers, versions, headers, events, policy);
	}

	static UpdateExpenseDetailsUseCase updateExpenseDetails(ExpenseContextPort contexts, ExpenseHeaderPort headers,
			PotGlobalVersionPort versions, EventPublisherPort events, PotAuthorizationGuard policy) {
		return new UpdateExpenseDetailsService(contexts, headers, versions, headers, events, policy);
	}

	static UpdateExpenseSharesUseCase updateExpenseShares(ExpenseContextPort contexts, ExpenseSharesPort shares,
			PotGlobalVersionPort versions, EventPublisherPort events, PotAuthorizationGuard policy) {
		return new UpdateExpenseSharesService(contexts, shares, versions, shares, events, policy);
	}

	static UpdatePotShareholdersDetailsUseCase updateShareholderDetails(
			PotContextPort contexts, PotShareholdersPort shareholders, PotGlobalVersionPort versions,
			EventPublisherPort events, PotAuthorizationGuard policy) {
		return new UpdatePotShareholdersDetailsService(
				contexts, shareholders, versions, shareholders, events, policy);
	}

	static UpdatePotShareholdersWeightsUseCase updateShareholderWeights(
			PotContextPort contexts, PotShareholdersPort shareholders, PotGlobalVersionPort versions,
			EventPublisherPort events, PotAuthorizationGuard policy) {
		return new UpdatePotShareholdersWeightsService(
				contexts, shareholders, versions, shareholders, events, policy);
	}
}
