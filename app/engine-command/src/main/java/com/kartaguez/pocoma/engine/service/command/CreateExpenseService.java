package com.kartaguez.pocoma.engine.service.command;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.kartaguez.pocoma.domain.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.association.ExpenseShare;
import com.kartaguez.pocoma.domain.created.ExpenseCreated;
import com.kartaguez.pocoma.domain.draft.ExpenseShareDraft;
import com.kartaguez.pocoma.domain.factory.ExpenseFactory;
import com.kartaguez.pocoma.domain.policy.CreateExpenseAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.Amount;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.context.CreateExpenseContext;
import com.kartaguez.pocoma.engine.event.ExpenseCreatedEvent;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreateExpenseCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.ExpenseSharesSnapshot;
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
	private final CreateExpenseAuthorizationPolicy createExpenseAuthorizationPolicy;

	CreateExpenseService(
			PotContextPort loadCreateExpenseContextPort,
			PotGlobalVersionPort updatePotGlobalVersionPort,
			ExpenseHeaderPort saveExpenseHeaderPort,
			ExpenseSharesPort saveExpenseSharesPort,
			EventPublisherPort publishExpenseCreatedEventPort,
			CreateExpenseAuthorizationPolicy createExpenseAuthorizationPolicy) {
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
		this.createExpenseAuthorizationPolicy = Objects.requireNonNull(
				createExpenseAuthorizationPolicy,
				"createExpenseAuthorizationPolicy must not be null");
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
		createExpenseAuthorizationPolicy.assertCanCreateExpense(userContext.userId(), context.creatorId());

		// 5. Create the domain creation result.
		ExpenseCreated expenseCreated = ExpenseFactory.createExpense(
				potId,
				payerId,
				Amount.of(Fraction.of(command.amountNumerator(), command.amountDenominator())),
				Label.of(command.label()),
				shareDrafts);

		// 6. Build the two aggregates created by this same logical mutation.
		ExpenseHeader expenseHeader = ExpenseHeader.reconstitute(
				expenseCreated.id(),
				expenseCreated.potId(),
				expenseCreated.payerId(),
				expenseCreated.amount(),
				expenseCreated.label(),
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
