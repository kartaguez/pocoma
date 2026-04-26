package com.kartaguez.pocoma.engine.service.command;

import java.util.Objects;

import com.kartaguez.pocoma.domain.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.policy.DeleteExpenseAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.context.DeleteExpenseContext;
import com.kartaguez.pocoma.engine.event.ExpenseDeletedEvent;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.in.command.intent.DeleteExpenseCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.usecase.DeleteExpenseUseCase;
import com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseHeaderPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.engine.security.UserContext;

final class DeleteExpenseService implements DeleteExpenseUseCase {

	private final ExpenseContextPort loadDeleteExpenseContextPort;
	private final ExpenseHeaderPort loadExpenseHeaderPort;
	private final PotGlobalVersionPort updatePotGlobalVersionPort;
	private final ExpenseHeaderPort replaceExpenseHeaderPort;
	private final EventPublisherPort publishExpenseDeletedEventPort;
	private final DeleteExpenseAuthorizationPolicy deleteExpenseAuthorizationPolicy;

	DeleteExpenseService(
			ExpenseContextPort loadDeleteExpenseContextPort,
			ExpenseHeaderPort loadExpenseHeaderPort,
			PotGlobalVersionPort updatePotGlobalVersionPort,
			ExpenseHeaderPort replaceExpenseHeaderPort,
			EventPublisherPort publishExpenseDeletedEventPort,
			DeleteExpenseAuthorizationPolicy deleteExpenseAuthorizationPolicy) {
		this.loadDeleteExpenseContextPort = Objects.requireNonNull(
				loadDeleteExpenseContextPort,
				"loadDeleteExpenseContextPort must not be null");
		this.loadExpenseHeaderPort = Objects.requireNonNull(
				loadExpenseHeaderPort,
				"loadExpenseHeaderPort must not be null");
		this.updatePotGlobalVersionPort = Objects.requireNonNull(
				updatePotGlobalVersionPort,
				"updatePotGlobalVersionPort must not be null");
		this.replaceExpenseHeaderPort = Objects.requireNonNull(
				replaceExpenseHeaderPort,
				"replaceExpenseHeaderPort must not be null");
		this.publishExpenseDeletedEventPort = Objects.requireNonNull(
				publishExpenseDeletedEventPort,
				"publishExpenseDeletedEventPort must not be null");
		this.deleteExpenseAuthorizationPolicy = Objects.requireNonNull(
				deleteExpenseAuthorizationPolicy,
				"deleteExpenseAuthorizationPolicy must not be null");
	}

	@Override
	public ExpenseHeaderSnapshot deleteExpense(UserContext userContext, DeleteExpenseCommand command) {
		// 1. Validate the incoming application command.
		Objects.requireNonNull(command, "command must not be null");
		Objects.requireNonNull(userContext, "userContext must not be null");

		// 2. Load the precondition context needed by this modification use case.
		ExpenseId expenseId = ExpenseId.of(command.expenseId());
		DeleteExpenseContext context = Objects.requireNonNull(
				loadDeleteExpenseContextPort.loadDeleteExpenseContext(expenseId),
				"deleteExpenseContext must not be null");
		PotGlobalVersion currentVersion = context.potGlobalVersion();
		PotId potId = currentVersion.potId();

		// 3. Check state and optimistic version preconditions.
		context.assertDeletePreconditions(command.expectedVersion());

		// 4. Check that the current user is allowed to delete this expense.
		deleteExpenseAuthorizationPolicy.assertCanDeleteExpense(userContext.userId(), context.creatorId());

		// 5. Load the full expense header active at the explicit working version.
		ExpenseHeader currentExpenseHeader = Objects.requireNonNull(
				loadExpenseHeaderPort.loadActiveAtVersion(expenseId, currentVersion.version()),
				"expenseHeader must not be null");

		// 6. Mutate the active domain aggregate.
		currentExpenseHeader.markAsDeleted();

		// 7. Increment the global version and persist the new aggregate state.
		long nextVersionNumber = currentVersion.version() + 1;
		PotGlobalVersion nextVersion = new PotGlobalVersion(potId, nextVersionNumber);

		// 8. Persist only if the explicit working version is still active.
		updatePotGlobalVersionPort.updateIfActive(currentVersion, nextVersion);
		replaceExpenseHeaderPort.save(currentExpenseHeader, currentVersion, nextVersion);

		// 9. Publish the business event for projection workers.
		publishExpenseDeletedEventPort.publish(new ExpenseDeletedEvent(expenseId, potId, nextVersionNumber));

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
