package com.kartaguez.pocoma.engine.service.command;

import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.domain.pot.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.pot.policy.UpdateExpenseDetailsAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.value.Amount;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.context.UpdateExpenseDetailsContext;
import com.kartaguez.pocoma.domain.pot.event.ExpenseDetailsUpdatedEvent;
import com.kartaguez.pocoma.engine.pot.version.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdateExpenseDetailsCommand;
import com.kartaguez.pocoma.engine.snapshot.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdateExpenseDetailsUseCase;
import com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseHeaderPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.engine.security.UserContext;

final class UpdateExpenseDetailsService implements UpdateExpenseDetailsUseCase {

	private final ExpenseContextPort loadUpdateExpenseDetailsContextPort;
	private final ExpenseHeaderPort loadExpenseHeaderPort;
	private final PotGlobalVersionPort updatePotGlobalVersionPort;
	private final ExpenseHeaderPort replaceExpenseHeaderPort;
	private final EventPublisherPort publishExpenseDetailsUpdatedEventPort;
	private final UpdateExpenseDetailsAuthorizationPolicy updateExpenseDetailsAuthorizationPolicy;

	UpdateExpenseDetailsService(
			ExpenseContextPort loadUpdateExpenseDetailsContextPort,
			ExpenseHeaderPort loadExpenseHeaderPort,
			PotGlobalVersionPort updatePotGlobalVersionPort,
			ExpenseHeaderPort replaceExpenseHeaderPort,
			EventPublisherPort publishExpenseDetailsUpdatedEventPort,
			UpdateExpenseDetailsAuthorizationPolicy updateExpenseDetailsAuthorizationPolicy) {
		this.loadUpdateExpenseDetailsContextPort = Objects.requireNonNull(
				loadUpdateExpenseDetailsContextPort,
				"loadUpdateExpenseDetailsContextPort must not be null");
		this.loadExpenseHeaderPort = Objects.requireNonNull(
				loadExpenseHeaderPort,
				"loadExpenseHeaderPort must not be null");
		this.updatePotGlobalVersionPort = Objects.requireNonNull(
				updatePotGlobalVersionPort,
				"updatePotGlobalVersionPort must not be null");
		this.replaceExpenseHeaderPort = Objects.requireNonNull(
				replaceExpenseHeaderPort,
				"replaceExpenseHeaderPort must not be null");
		this.publishExpenseDetailsUpdatedEventPort = Objects.requireNonNull(
				publishExpenseDetailsUpdatedEventPort,
				"publishExpenseDetailsUpdatedEventPort must not be null");
		this.updateExpenseDetailsAuthorizationPolicy = Objects.requireNonNull(
				updateExpenseDetailsAuthorizationPolicy,
				"updateExpenseDetailsAuthorizationPolicy must not be null");
	}

	@Override
	public ExpenseHeaderSnapshot updateExpenseDetails(UserContext userContext, UpdateExpenseDetailsCommand command) {
		// 1. Validate the incoming application command.
		Objects.requireNonNull(command, "command must not be null");
		Objects.requireNonNull(userContext, "userContext must not be null");

		// 2. Convert command identifiers and load the precondition context.
		ExpenseId expenseId = ExpenseId.of(command.expenseId());
		ShareholderId payerId = ShareholderId.of(command.payerId());
		UpdateExpenseDetailsContext context = Objects.requireNonNull(
				loadUpdateExpenseDetailsContextPort.loadUpdateExpenseDetailsContext(expenseId),
				"updateExpenseDetailsContext must not be null");
		PotGlobalVersion currentVersion = context.potGlobalVersion();
		PotId potId = currentVersion.potId();

		// 3. Check expense state, optimistic version and payer membership.
		context.assertUpdatePreconditions(command.expectedVersion(), payerId);

		// 4. Check that the current user is allowed to update this expense.
		updateExpenseDetailsAuthorizationPolicy.assertCanUpdateExpenseDetails(
				userContext.userId(),
				userContext.scopes(),
				context.creatorId(),
				Set.of());

		// 5. Load the full expense header active at the explicit working version.
		ExpenseHeader currentExpenseHeader = Objects.requireNonNull(
				loadExpenseHeaderPort.loadActiveAtVersion(expenseId, currentVersion.version()),
				"expenseHeader must not be null");

		// 6. Mutate the active domain aggregate.
		currentExpenseHeader.updateDetails(
				payerId,
				Amount.of(Fraction.of(command.amountNumerator(), command.amountDenominator())),
				Label.of(command.label()));

		// 7. Increment the global version and persist the new aggregate state.
		long nextVersionNumber = currentVersion.version() + 1;
		PotGlobalVersion nextVersion = new PotGlobalVersion(potId, nextVersionNumber);

		// 8. Persist only if the explicit working version is still active.
		updatePotGlobalVersionPort.updateIfActive(currentVersion, nextVersion);
		replaceExpenseHeaderPort.save(currentExpenseHeader, currentVersion, nextVersion);

		// 9. Publish the business event for projection workers.
		publishExpenseDetailsUpdatedEventPort.publish(new ExpenseDetailsUpdatedEvent(
				expenseId,
				potId,
				nextVersionNumber));

		// 10. Return a versioned snapshot to the caller.
		return new ExpenseHeaderSnapshot(
				currentExpenseHeader.id(),
				currentExpenseHeader.potId(),
				currentExpenseHeader.payerId(),
				currentExpenseHeader.amount(),
				currentExpenseHeader.label(),
				currentExpenseHeader.deleted(),
				nextVersionNumber);
	}
}
