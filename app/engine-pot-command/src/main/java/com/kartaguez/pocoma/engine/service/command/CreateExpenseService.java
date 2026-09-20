package com.kartaguez.pocoma.engine.service.command;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.kartaguez.pocoma.domain.pot.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.pot.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.pot.association.ExpenseShare;
import com.kartaguez.pocoma.domain.pot.created.ExpenseCreated;
import com.kartaguez.pocoma.domain.pot.draft.ExpenseShareDraft;
import com.kartaguez.pocoma.domain.pot.factory.ExpenseFactory;
import com.kartaguez.pocoma.domain.pot.authorization.AuthorizationTarget;
import com.kartaguez.pocoma.domain.pot.authorization.PotAction;
import com.kartaguez.pocoma.domain.pot.authorization.PotAuthorizationRelations;
import com.kartaguez.pocoma.domain.pot.value.Amount;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.context.CreateExpenseContext;
import com.kartaguez.pocoma.domain.pot.event.ExpenseCreatedEvent;
import com.kartaguez.pocoma.engine.pot.version.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreateExpenseCommand;
import com.kartaguez.pocoma.engine.snapshot.ExpenseSharesSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.usecase.CreateExpenseUseCase;
import com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseHeaderPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseSharesPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.engine.security.UserContext;

final class CreateExpenseService implements CreateExpenseUseCase {

	private final PotContextPort loadCreateExpenseContextPort;
	private final PotGlobalVersionPort updatePotGlobalVersionPort;
	private final ExpenseHeaderPort saveExpenseHeaderPort;
	private final ExpenseSharesPort saveExpenseSharesPort;
	private final EventPublisherPort publishExpenseCreatedEventPort;
	private final PotAuthorizationGuard authorizationGuard;

	CreateExpenseService(
			PotContextPort loadCreateExpenseContextPort,
			PotGlobalVersionPort updatePotGlobalVersionPort,
			ExpenseHeaderPort saveExpenseHeaderPort,
			ExpenseSharesPort saveExpenseSharesPort,
			EventPublisherPort publishExpenseCreatedEventPort,
			PotAuthorizationGuard authorizationGuard) {
		this.loadCreateExpenseContextPort = Objects.requireNonNull(
				loadCreateExpenseContextPort,
				"loadCreateExpenseContextPort must not be null");
		this.updatePotGlobalVersionPort = Objects.requireNonNull(
				updatePotGlobalVersionPort,
				"updatePotGlobalVersionPort must not be null");
		this.saveExpenseHeaderPort = Objects.requireNonNull(
				saveExpenseHeaderPort,
				"saveExpenseHeaderPort must not be null");
		this.saveExpenseSharesPort = Objects.requireNonNull(
				saveExpenseSharesPort,
				"saveExpenseSharesPort must not be null");
		this.publishExpenseCreatedEventPort = Objects.requireNonNull(
				publishExpenseCreatedEventPort,
				"publishExpenseCreatedEventPort must not be null");
		this.authorizationGuard = Objects.requireNonNull(authorizationGuard, "authorizationGuard must not be null");
	}

	@Override
	public ExpenseSharesSnapshot createExpense(UserContext userContext, CreateExpenseCommand command) {
		// 1. Validate the incoming application command.
		Objects.requireNonNull(command, "command must not be null");
		Objects.requireNonNull(userContext, "userContext must not be null");

		// 2. Convert command identifiers and load the precondition context.
		PotId potId = PotId.of(command.potId());
		ShareholderId payerId = ShareholderId.of(command.payerId());
		Set<ExpenseShareDraft> shareDrafts = toShareDrafts(command);
		Set<ShareholderId> expenseShareholderIds = shareDrafts.stream()
				.map(ExpenseShareDraft::shareholderId)
				.collect(Collectors.toSet());
		CreateExpenseContext context = Objects.requireNonNull(
				loadCreateExpenseContextPort.loadCreateExpenseContext(potId),
				"createExpenseContext must not be null");
		PotGlobalVersion currentVersion = context.potGlobalVersion();

		// 3. Check pot state, optimistic version, payer and shares membership.
		context.assertCreatePreconditions(command.expectedVersion(), payerId, expenseShareholderIds);

		// 4. Check that the current user is allowed to create an expense in this pot.
		authorizationGuard.assertAuthorized(
				userContext.userId(),
				userContext.permissions(),
				new PotAuthorizationRelations(potId, context.creatorId(), context.shareholderUsers()),
				AuthorizationTarget.prospectiveExpense(),
				PotAction.CREATE_EXPENSE,
				"EXPENSE_CREATE_FORBIDDEN",
				"Only the pot creator or a shareholder can create an expense");

		// 5. Create the domain creation result.
		ExpenseCreated expenseCreated = ExpenseFactory.createExpense(
				potId,
				payerId,
				Amount.of(Fraction.of(command.amountNumerator(), command.amountDenominator())),
				Label.of(command.label()),
				command.date(),
				shareDrafts);

		// 6. Build the two aggregates created by this same logical mutation.
		ExpenseHeader expenseHeader = ExpenseHeader.reconstitute(
				expenseCreated.id(),
				expenseCreated.potId(),
				expenseCreated.payerId(),
				expenseCreated.amount(),
				expenseCreated.label(),
				expenseCreated.date(),
				false);
		ExpenseShares expenseShares = ExpenseShares.reconstitute(
				expenseCreated.potId(),
				expenseCreated.shares().stream()
						.map(share -> new ExpenseShare(expenseCreated.id(), share.shareholderId(), share.weight()))
						.collect(Collectors.toSet()));

		// 7. Increment the global version once for both aggregate writes.
		long nextVersionNumber = currentVersion.version() + 1;
		PotGlobalVersion nextVersion = new PotGlobalVersion(potId, nextVersionNumber);

		// 8. Persist the version bump and both new aggregate instances at the same version.
		updatePotGlobalVersionPort.updateIfActive(currentVersion, nextVersion);
		saveExpenseHeaderPort.saveNew(expenseHeader, nextVersionNumber);
		saveExpenseSharesPort.saveNew(expenseCreated.id(), expenseShares, nextVersionNumber);

		// 9. Publish the business event for projection workers.
		publishExpenseCreatedEventPort.publish(new ExpenseCreatedEvent(
				expenseCreated.id(),
				expenseCreated.potId(),
				nextVersionNumber));

		// 10. Return the created shares snapshot.
		return new ExpenseSharesSnapshot(
				expenseCreated.id(),
				expenseShares.potId(),
				expenseShares.shares(),
				nextVersionNumber);
	}

	private static Set<ExpenseShareDraft> toShareDrafts(CreateExpenseCommand command) {
		return command.shares().stream()
				.map(share -> new ExpenseShareDraft(
						ShareholderId.of(share.shareholderId()),
						Weight.of(Fraction.of(share.weightNumerator(), share.weightDenominator()))))
				.collect(Collectors.toSet());
	}
}
