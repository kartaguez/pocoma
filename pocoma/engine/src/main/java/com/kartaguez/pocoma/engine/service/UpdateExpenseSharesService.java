package com.kartaguez.pocoma.engine.service;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.kartaguez.pocoma.domain.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.association.ExpenseShare;
import com.kartaguez.pocoma.domain.policy.UpdateExpenseSharesAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.context.UpdateExpenseSharesContext;
import com.kartaguez.pocoma.engine.event.ExpenseSharesUpdatedEvent;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.in.intent.UpdateExpenseSharesCommand;
import com.kartaguez.pocoma.engine.port.in.result.ExpenseSharesSnapshot;
import com.kartaguez.pocoma.engine.port.in.usecase.UpdateExpenseSharesUseCase;
import com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseSharesPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.engine.security.UserContext;

public final class UpdateExpenseSharesService implements UpdateExpenseSharesUseCase {

	private final ExpenseContextPort loadUpdateExpenseSharesContextPort;
	private final ExpenseSharesPort loadExpenseSharesPort;
	private final PotGlobalVersionPort updatePotGlobalVersionPort;
	private final ExpenseSharesPort replaceExpenseSharesPort;
	private final EventPublisherPort publishExpenseSharesUpdatedEventPort;
	private final UpdateExpenseSharesAuthorizationPolicy updateExpenseSharesAuthorizationPolicy;

	public UpdateExpenseSharesService(
			ExpenseContextPort loadUpdateExpenseSharesContextPort,
			ExpenseSharesPort loadExpenseSharesPort,
			PotGlobalVersionPort updatePotGlobalVersionPort,
			ExpenseSharesPort replaceExpenseSharesPort,
			EventPublisherPort publishExpenseSharesUpdatedEventPort,
			UpdateExpenseSharesAuthorizationPolicy updateExpenseSharesAuthorizationPolicy) {
		this.loadUpdateExpenseSharesContextPort = Objects.requireNonNull(
				loadUpdateExpenseSharesContextPort,
				"loadUpdateExpenseSharesContextPort must not be null");
		this.loadExpenseSharesPort = Objects.requireNonNull(
				loadExpenseSharesPort,
				"loadExpenseSharesPort must not be null");
		this.updatePotGlobalVersionPort = Objects.requireNonNull(
				updatePotGlobalVersionPort,
				"updatePotGlobalVersionPort must not be null");
		this.replaceExpenseSharesPort = Objects.requireNonNull(
				replaceExpenseSharesPort,
				"replaceExpenseSharesPort must not be null");
		this.publishExpenseSharesUpdatedEventPort = Objects.requireNonNull(
				publishExpenseSharesUpdatedEventPort,
				"publishExpenseSharesUpdatedEventPort must not be null");
		this.updateExpenseSharesAuthorizationPolicy = Objects.requireNonNull(
				updateExpenseSharesAuthorizationPolicy,
				"updateExpenseSharesAuthorizationPolicy must not be null");
	}

	@Override
	public ExpenseSharesSnapshot updateExpenseShares(UserContext userContext, UpdateExpenseSharesCommand command) {
		// 1. Validate the incoming application command.
		Objects.requireNonNull(command, "command must not be null");
		Objects.requireNonNull(userContext, "userContext must not be null");

		// 2. Convert command identifiers and load the precondition context.
		ExpenseId expenseId = ExpenseId.of(command.expenseId());
		Set<ExpenseShare> requestedShares = toExpenseShares(expenseId, command);
		Set<ShareholderId> expenseShareholderIds = requestedShares.stream()
				.map(ExpenseShare::shareholderId)
				.collect(Collectors.toSet());
		UpdateExpenseSharesContext context = Objects.requireNonNull(
				loadUpdateExpenseSharesContextPort.loadUpdateExpenseSharesContext(expenseId),
				"updateExpenseSharesContext must not be null");
		PotGlobalVersion currentVersion = context.potGlobalVersion();
		PotId potId = currentVersion.potId();

		// 3. Check expense state, optimistic version and shares membership.
		context.assertUpdatePreconditions(command.expectedVersion(), expenseShareholderIds);

		// 4. Check that the current user is allowed to update this expense.
		updateExpenseSharesAuthorizationPolicy.assertCanUpdateExpenseShares(
				userContext.userId(),
				context.creatorId());

		// 5. Load the full expense shares aggregate active at the explicit working version.
		ExpenseShares currentExpenseShares = Objects.requireNonNull(
				loadExpenseSharesPort.loadActiveAtVersion(expenseId, currentVersion.version()),
				"expenseShares must not be null");

		// 6. Replace active shares with command shares.
		currentExpenseShares.updateExpenseShares(context.shareholderIds(), requestedShares);

		// 7. Increment the global version and persist the new aggregate state.
		long nextVersionNumber = currentVersion.version() + 1;
		PotGlobalVersion nextVersion = new PotGlobalVersion(potId, nextVersionNumber);

		// 8. Persist only if the explicit working version is still active.
		updatePotGlobalVersionPort.updateIfActive(currentVersion, nextVersion);
		replaceExpenseSharesPort.save(expenseId, currentExpenseShares, currentVersion, nextVersion);

		// 9. Publish the business event for projection workers.
		publishExpenseSharesUpdatedEventPort.publish(new ExpenseSharesUpdatedEvent(
				expenseId,
				potId,
				nextVersionNumber));

		// 10. Return a versioned snapshot to the caller.
		return new ExpenseSharesSnapshot(
				expenseId,
				currentExpenseShares.potId(),
				currentExpenseShares.shares(),
				nextVersionNumber);
	}

	private static Set<ExpenseShare> toExpenseShares(ExpenseId expenseId, UpdateExpenseSharesCommand command) {
		return command.shares().stream()
				.map(share -> new ExpenseShare(
						expenseId,
						ShareholderId.of(share.shareholderId()),
						Weight.of(Fraction.of(share.weightNumerator(), share.weightDenominator()))))
				.collect(Collectors.toSet());
	}
}
