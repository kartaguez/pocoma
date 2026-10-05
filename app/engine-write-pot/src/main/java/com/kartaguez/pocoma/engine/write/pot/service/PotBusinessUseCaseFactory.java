package com.kartaguez.pocoma.engine.write.pot.service;

import com.kartaguez.pocoma.domain.pot.policy.CreatePotAuthorizationPolicy;
import com.kartaguez.pocoma.engine.write.pot.usecase.AddPotShareholdersUseCase;
import com.kartaguez.pocoma.engine.write.pot.usecase.CreateExpenseUseCase;
import com.kartaguez.pocoma.engine.write.pot.usecase.CreatePotUseCase;
import com.kartaguez.pocoma.engine.write.pot.usecase.DeleteExpenseUseCase;
import com.kartaguez.pocoma.engine.write.pot.usecase.DeletePotUseCase;
import com.kartaguez.pocoma.engine.write.pot.usecase.UpdateExpenseDetailsUseCase;
import com.kartaguez.pocoma.engine.write.pot.usecase.UpdateExpenseSharesUseCase;
import com.kartaguez.pocoma.engine.write.pot.usecase.UpdatePotDetailsUseCase;
import com.kartaguez.pocoma.engine.write.pot.usecase.UpdatePotShareholdersDetailsUseCase;
import com.kartaguez.pocoma.engine.write.pot.usecase.UpdatePotShareholdersWeightsUseCase;
import com.kartaguez.pocoma.engine.write.pot.port.event.EventPublisherPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.ExpenseContextPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.ExpenseHeaderPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.ExpenseSharesPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.PotContextPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.PotHeaderPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.PotShareholdersPort;

/** Builds raw Pot business ports with invocation-local collaborators. */
public final class PotBusinessUseCaseFactory {

	private PotBusinessUseCaseFactory() {
	}

	public static CreatePotUseCase createPot(
			PotGlobalVersionPort versions,
			PotHeaderPort headers,
			EventPublisherPort events,
			CreatePotAuthorizationPolicy policy) {
		return new CreatePotService(versions, headers, events, policy);
	}

	public static CreateExpenseUseCase createExpense(
			PotContextPort contexts,
			PotGlobalVersionPort versions,
			ExpenseHeaderPort headers,
			ExpenseSharesPort shares,
			EventPublisherPort events,
			PotAuthorizationGuard policy) {
		return new CreateExpenseService(contexts, versions, headers, shares, events, policy);
	}

	public static AddPotShareholdersUseCase addShareholders(
			PotContextPort contexts,
			PotShareholdersPort shareholders,
			PotGlobalVersionPort versions,
			EventPublisherPort events,
			PotAuthorizationGuard policy) {
		return new AddPotShareholdersService(contexts, shareholders, versions, shareholders, events, policy);
	}

	public static DeletePotUseCase deletePot(PotContextPort contexts, PotHeaderPort headers,
			PotGlobalVersionPort versions, EventPublisherPort events, PotAuthorizationGuard policy) {
		return new DeletePotService(contexts, headers, versions, headers, events, policy);
	}

	public static DeleteExpenseUseCase deleteExpense(ExpenseContextPort contexts, ExpenseHeaderPort headers,
			PotGlobalVersionPort versions, EventPublisherPort events, PotAuthorizationGuard policy) {
		return new DeleteExpenseService(contexts, headers, versions, headers, events, policy);
	}

	public static UpdatePotDetailsUseCase updatePotDetails(PotContextPort contexts, PotHeaderPort headers,
			PotGlobalVersionPort versions, EventPublisherPort events, PotAuthorizationGuard policy) {
		return new UpdatePotDetailsService(contexts, headers, versions, headers, events, policy);
	}

	public static UpdateExpenseDetailsUseCase updateExpenseDetails(ExpenseContextPort contexts, ExpenseHeaderPort headers,
			PotGlobalVersionPort versions, EventPublisherPort events, PotAuthorizationGuard policy) {
		return new UpdateExpenseDetailsService(contexts, headers, versions, headers, events, policy);
	}

	public static UpdateExpenseSharesUseCase updateExpenseShares(ExpenseContextPort contexts, ExpenseSharesPort shares,
			PotGlobalVersionPort versions, EventPublisherPort events, PotAuthorizationGuard policy) {
		return new UpdateExpenseSharesService(contexts, shares, versions, shares, events, policy);
	}

	public static UpdatePotShareholdersDetailsUseCase updateShareholderDetails(
			PotContextPort contexts, PotShareholdersPort shareholders, PotGlobalVersionPort versions,
			EventPublisherPort events, PotAuthorizationGuard policy) {
		return new UpdatePotShareholdersDetailsService(
				contexts, shareholders, versions, shareholders, events, policy);
	}

	public static UpdatePotShareholdersWeightsUseCase updateShareholderWeights(
			PotContextPort contexts, PotShareholdersPort shareholders, PotGlobalVersionPort versions,
			EventPublisherPort events, PotAuthorizationGuard policy) {
		return new UpdatePotShareholdersWeightsService(
				contexts, shareholders, versions, shareholders, events, policy);
	}
}
