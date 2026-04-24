package com.kartaguez.pocoma.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.domain.policy.CreateExpenseAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.context.CreateExpenseContext;
import com.kartaguez.pocoma.engine.event.ExpenseCreatedEvent;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.in.intent.CreateExpenseCommand;
import com.kartaguez.pocoma.engine.port.in.result.ExpenseSharesSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

class CreateExpenseServiceTest {

	@Test
	void createsExpense() {
		CreateExpenseFixture fixture = new CreateExpenseFixture();
		FakePotContextPort loadContextPort =
				new FakePotContextPort(fixture.context(false));
		FakePotGlobalVersionPort updatePotGlobalVersionPort = new FakePotGlobalVersionPort();
		FakeExpenseHeaderPort saveExpenseHeaderPort = new FakeExpenseHeaderPort();
		FakeExpenseSharesPort saveExpenseSharesPort = new FakeExpenseSharesPort();
		FakeEventPublisherPort publishExpenseCreatedEventPort = new FakeEventPublisherPort();
		CreateExpenseService service = new CreateExpenseService(
				loadContextPort,
				updatePotGlobalVersionPort,
				saveExpenseHeaderPort,
				saveExpenseSharesPort,
				publishExpenseCreatedEventPort,
				new CreateExpenseAuthorizationPolicy());

		ExpenseSharesSnapshot snapshot = service.createExpense(
				new UserContext(fixture.creatorId.value().toString()),
				fixture.command(3, fixture.payerId, fixture.shareholderId));

		assertNotNull(snapshot.expenseId());
		assertEquals(fixture.potId, snapshot.potId());
		assertEquals(1, snapshot.shares().size());
		assertEquals(4, snapshot.version());
		assertEquals(fixture.potId, loadContextPort.loadedPotId);
		assertEquals(new PotGlobalVersion(fixture.potId, 3), updatePotGlobalVersionPort.expectedActiveVersion);
		assertEquals(new PotGlobalVersion(fixture.potId, 4), updatePotGlobalVersionPort.nextVersion);
		assertEquals(4, saveExpenseHeaderPort.savedVersion);
		assertEquals(snapshot.expenseId(), saveExpenseHeaderPort.saved.id());
		assertEquals(fixture.payerId, saveExpenseHeaderPort.saved.payerId());
		assertFalse(saveExpenseHeaderPort.saved.deleted());
		assertEquals(4, saveExpenseSharesPort.savedVersion);
		assertEquals(snapshot.expenseId(), saveExpenseSharesPort.savedExpenseId);
		assertEquals(snapshot.expenseId(), saveExpenseSharesPort.saved.shares().values().iterator().next().expenseId());
		assertEquals(new ExpenseCreatedEvent(snapshot.expenseId(), fixture.potId, 4), publishExpenseCreatedEventPort.published);
	}

	@Test
	void rejectsAlreadyDeletedPotWithoutSavingExpense() {
		CreateExpenseFixture fixture = new CreateExpenseFixture();
		FakeExpenseHeaderPort saveExpenseHeaderPort = new FakeExpenseHeaderPort();
		FakeExpenseSharesPort saveExpenseSharesPort = new FakeExpenseSharesPort();
		CreateExpenseService service = fixture.service(fixture.context(true), saveExpenseHeaderPort, saveExpenseSharesPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> service.createExpense(
						new UserContext(fixture.creatorId.value().toString()),
						fixture.command(3, fixture.payerId, fixture.shareholderId)));

		assertEquals("POT_ALREADY_DELETED", exception.ruleCode());
		assertNull(saveExpenseHeaderPort.saved);
		assertNull(saveExpenseSharesPort.saved);
	}

	@Test
	void rejectsVersionConflictWithoutSavingExpense() {
		CreateExpenseFixture fixture = new CreateExpenseFixture();
		FakeExpenseHeaderPort saveExpenseHeaderPort = new FakeExpenseHeaderPort();
		FakeExpenseSharesPort saveExpenseSharesPort = new FakeExpenseSharesPort();
		CreateExpenseService service = fixture.service(fixture.context(false), saveExpenseHeaderPort, saveExpenseSharesPort);

		VersionConflictException exception = assertThrows(
				VersionConflictException.class,
				() -> service.createExpense(
						new UserContext(fixture.creatorId.value().toString()),
						fixture.command(2, fixture.payerId, fixture.shareholderId)));

		assertEquals("POT_VERSION_CONFLICT", exception.conflictCode());
		assertNull(saveExpenseHeaderPort.saved);
		assertNull(saveExpenseSharesPort.saved);
	}

	@Test
	void rejectsUnknownShareholderWithoutSavingExpense() {
		CreateExpenseFixture fixture = new CreateExpenseFixture();
		FakeExpenseHeaderPort saveExpenseHeaderPort = new FakeExpenseHeaderPort();
		FakeExpenseSharesPort saveExpenseSharesPort = new FakeExpenseSharesPort();
		CreateExpenseService service = fixture.service(fixture.context(false), saveExpenseHeaderPort, saveExpenseSharesPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> service.createExpense(
						new UserContext(fixture.creatorId.value().toString()),
						fixture.command(3, fixture.payerId, ShareholderId.of(UUID.randomUUID()))));

		assertEquals("SHAREHOLDER_NOT_PRESENT", exception.ruleCode());
		assertNull(saveExpenseHeaderPort.saved);
		assertNull(saveExpenseSharesPort.saved);
	}

	@Test
	void rejectsForbiddenUserWithoutSavingExpense() {
		CreateExpenseFixture fixture = new CreateExpenseFixture();
		FakeExpenseHeaderPort saveExpenseHeaderPort = new FakeExpenseHeaderPort();
		FakeExpenseSharesPort saveExpenseSharesPort = new FakeExpenseSharesPort();
		CreateExpenseService service = fixture.service(fixture.context(false), saveExpenseHeaderPort, saveExpenseSharesPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> service.createExpense(
						new UserContext(UUID.randomUUID().toString()),
						fixture.command(3, fixture.payerId, fixture.shareholderId)));

		assertEquals("EXPENSE_CREATE_FORBIDDEN", exception.ruleCode());
		assertNull(saveExpenseHeaderPort.saved);
		assertNull(saveExpenseSharesPort.saved);
	}

	private static final class CreateExpenseFixture {
		private final PotId potId = PotId.of(UUID.randomUUID());
		private final UserId creatorId = UserId.of(UUID.randomUUID());
		private final ShareholderId payerId = ShareholderId.of(UUID.randomUUID());
		private final ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());

		private CreateExpenseContext context(boolean deleted) {
			return new CreateExpenseContext(
					new PotGlobalVersion(potId, 3),
					deleted,
					creatorId,
					Set.of(payerId, shareholderId));
		}

		private CreateExpenseCommand command(
				long expectedVersion,
				ShareholderId payerId,
				ShareholderId shareholderId) {
			return new CreateExpenseCommand(
					potId.value(),
					payerId.value(),
					42,
					1,
					"Dinner",
					Set.of(new CreateExpenseCommand.ExpenseShareInput(shareholderId.value(), 1, 1)),
					expectedVersion);
		}

		private CreateExpenseService service(
				CreateExpenseContext context,
				FakeExpenseHeaderPort saveExpenseHeaderPort,
				FakeExpenseSharesPort saveExpenseSharesPort) {
			return new CreateExpenseService(
					new FakePotContextPort(context),
					new FakePotGlobalVersionPort(),
					saveExpenseHeaderPort,
					saveExpenseSharesPort,
					new FakeEventPublisherPort(),
					new CreateExpenseAuthorizationPolicy());
		}
	}

	private static final class FakePotContextPort
			implements com.kartaguez.pocoma.engine.port.out.persistence.PotContextPort {

		private final CreateExpenseContext context;
		private PotId loadedPotId;

		private FakePotContextPort(CreateExpenseContext context) {
			this.context = context;
		}

		@Override
		public CreateExpenseContext loadCreateExpenseContext(PotId potId) {
			loadedPotId = potId;
			return context;
		}
	}

	private static final class FakePotGlobalVersionPort
			implements com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort {

		private PotGlobalVersion expectedActiveVersion;
		private PotGlobalVersion nextVersion;

		@Override
		public void updateIfActive(PotGlobalVersion expectedActiveVersion, PotGlobalVersion nextVersion) {
			this.expectedActiveVersion = expectedActiveVersion;
			this.nextVersion = nextVersion;
		}
	}

	private static final class FakeExpenseHeaderPort
			implements com.kartaguez.pocoma.engine.port.out.persistence.ExpenseHeaderPort {

		private ExpenseHeader saved;
		private long savedVersion;

		@Override
		public void saveNew(ExpenseHeader expenseHeader, long version) {
			saved = expenseHeader;
			savedVersion = version;
		}
	}

	private static final class FakeExpenseSharesPort
			implements com.kartaguez.pocoma.engine.port.out.persistence.ExpenseSharesPort {

		private ExpenseShares saved;
		private ExpenseId savedExpenseId;
		private long savedVersion;

		@Override
		public void saveNew(ExpenseId expenseId, ExpenseShares expenseShares, long version) {
			savedExpenseId = expenseId;
			saved = expenseShares;
			savedVersion = version;
		}
	}

	private static final class FakeEventPublisherPort
			implements com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort {

		private ExpenseCreatedEvent published;

		@Override
		public void publish(ExpenseCreatedEvent event) {
			published = event;
		}
	}
}
