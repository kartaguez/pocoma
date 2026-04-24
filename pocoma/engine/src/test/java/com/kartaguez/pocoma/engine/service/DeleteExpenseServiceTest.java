package com.kartaguez.pocoma.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.domain.policy.DeleteExpenseAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.Amount;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.context.DeleteExpenseContext;
import com.kartaguez.pocoma.engine.event.ExpenseDeletedEvent;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.model.Versioned;
import com.kartaguez.pocoma.engine.port.in.intent.DeleteExpenseCommand;
import com.kartaguez.pocoma.engine.port.in.result.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

class DeleteExpenseServiceTest {

	@Test
	void deletesExpense() {
		DeleteExpenseFixture fixture = new DeleteExpenseFixture();
		FakeExpenseContextPort loadContextPort =
				new FakeExpenseContextPort(fixture.context(false));
		FakeExpenseHeaderPort loadExpenseHeaderPort =
				new FakeExpenseHeaderPort(fixture.versionedExpenseHeader(false));
		FakePotGlobalVersionPort updatePotGlobalVersionPort = new FakePotGlobalVersionPort();
		FakeRecordingExpenseHeaderPort replaceExpenseHeaderPort = new FakeRecordingExpenseHeaderPort();
		FakeEventPublisherPort publishExpenseDeletedEventPort = new FakeEventPublisherPort();
		DeleteExpenseService service = new DeleteExpenseService(
				loadContextPort,
				loadExpenseHeaderPort,
				updatePotGlobalVersionPort,
				replaceExpenseHeaderPort,
				publishExpenseDeletedEventPort,
				new DeleteExpenseAuthorizationPolicy());

		ExpenseHeaderSnapshot snapshot = service.deleteExpense(
				new UserContext(fixture.creatorId.value().toString()),
				new DeleteExpenseCommand(fixture.expenseId.value(), 3));

		assertEquals(fixture.expenseId, snapshot.id());
		assertEquals(fixture.potId, snapshot.potId());
		assertTrue(snapshot.deleted());
		assertEquals(4, snapshot.version());
		assertEquals(fixture.expenseId, loadContextPort.loadedExpenseId);
		assertEquals(fixture.expenseId, loadExpenseHeaderPort.loadedExpenseId);
		assertEquals(3, loadExpenseHeaderPort.loadedAtVersion);
		assertEquals(new PotGlobalVersion(fixture.potId, 3), updatePotGlobalVersionPort.expectedActiveVersion);
		assertEquals(new PotGlobalVersion(fixture.potId, 4), updatePotGlobalVersionPort.nextVersion);
		assertFalse(replaceExpenseHeaderPort.previous.value().deleted());
		assertEquals(1, replaceExpenseHeaderPort.previous.startedAtVersion());
		assertEquals(4L, replaceExpenseHeaderPort.previous.endedAtVersion());
		assertTrue(replaceExpenseHeaderPort.next.value().deleted());
		assertEquals(4, replaceExpenseHeaderPort.next.startedAtVersion());
		assertNull(replaceExpenseHeaderPort.next.endedAtVersion());
		assertEquals(new ExpenseDeletedEvent(fixture.expenseId, fixture.potId, 4), publishExpenseDeletedEventPort.published);
	}

	@Test
	void rejectsAlreadyDeletedExpenseWithoutLoadingFullExpenseHeader() {
		DeleteExpenseFixture fixture = new DeleteExpenseFixture();
		FakeExpenseHeaderPort loadExpenseHeaderPort =
				new FakeExpenseHeaderPort(fixture.versionedExpenseHeader(false));
		DeleteExpenseService service = fixture.service(fixture.context(true), loadExpenseHeaderPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> service.deleteExpense(
						new UserContext(fixture.creatorId.value().toString()),
						new DeleteExpenseCommand(fixture.expenseId.value(), 3)));

		assertEquals("EXPENSE_ALREADY_DELETED", exception.ruleCode());
		assertFalse(loadExpenseHeaderPort.loaded);
	}

	@Test
	void rejectsVersionConflictWithoutLoadingFullExpenseHeader() {
		DeleteExpenseFixture fixture = new DeleteExpenseFixture();
		FakeExpenseHeaderPort loadExpenseHeaderPort =
				new FakeExpenseHeaderPort(fixture.versionedExpenseHeader(false));
		DeleteExpenseService service = fixture.service(fixture.context(false), loadExpenseHeaderPort);

		VersionConflictException exception = assertThrows(
				VersionConflictException.class,
				() -> service.deleteExpense(
						new UserContext(fixture.creatorId.value().toString()),
						new DeleteExpenseCommand(fixture.expenseId.value(), 2)));

		assertEquals("POT_VERSION_CONFLICT", exception.conflictCode());
		assertFalse(loadExpenseHeaderPort.loaded);
	}

	@Test
	void rejectsForbiddenUserWithoutLoadingFullExpenseHeader() {
		DeleteExpenseFixture fixture = new DeleteExpenseFixture();
		FakeExpenseHeaderPort loadExpenseHeaderPort =
				new FakeExpenseHeaderPort(fixture.versionedExpenseHeader(false));
		DeleteExpenseService service = fixture.service(fixture.context(false), loadExpenseHeaderPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> service.deleteExpense(
						new UserContext(UUID.randomUUID().toString()),
						new DeleteExpenseCommand(fixture.expenseId.value(), 3)));

		assertEquals("EXPENSE_DELETE_FORBIDDEN", exception.ruleCode());
		assertFalse(loadExpenseHeaderPort.loaded);
	}

	private static final class DeleteExpenseFixture {
		private final PotId potId = PotId.of(UUID.randomUUID());
		private final ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		private final ShareholderId payerId = ShareholderId.of(UUID.randomUUID());
		private final UserId creatorId = UserId.of(UUID.randomUUID());
		private final Amount amount = Amount.of(Fraction.of(42, 1));
		private final Label label = Label.of("Dinner");

		private DeleteExpenseContext context(boolean deleted) {
			return new DeleteExpenseContext(new PotGlobalVersion(potId, 3), deleted, creatorId);
		}

		private Versioned<ExpenseHeader> versionedExpenseHeader(boolean deleted) {
			return new Versioned<>(
					ExpenseHeader.reconstitute(expenseId, potId, payerId, amount, label, deleted),
					1,
					null);
		}

		private DeleteExpenseService service(
				DeleteExpenseContext context,
				FakeExpenseHeaderPort loadExpenseHeaderPort) {
			return new DeleteExpenseService(
					new FakeExpenseContextPort(context),
					loadExpenseHeaderPort,
					new FakePotGlobalVersionPort(),
					new FakeRecordingExpenseHeaderPort(),
					new FakeEventPublisherPort(),
					new DeleteExpenseAuthorizationPolicy());
		}
	}

	private static final class FakeExpenseContextPort
			implements com.kartaguez.pocoma.engine.port.out.persistence.ExpenseContextPort {

		private final DeleteExpenseContext context;
		private ExpenseId loadedExpenseId;

		private FakeExpenseContextPort(DeleteExpenseContext context) {
			this.context = context;
		}

		@Override
		public DeleteExpenseContext loadDeleteExpenseContext(ExpenseId expenseId) {
			loadedExpenseId = expenseId;
			return context;
		}
	}

	private static final class FakeExpenseHeaderPort
			implements com.kartaguez.pocoma.engine.port.out.persistence.ExpenseHeaderPort {

		private final Versioned<ExpenseHeader> expenseHeader;
		private boolean loaded;
		private ExpenseId loadedExpenseId;
		private long loadedAtVersion;

		private FakeExpenseHeaderPort(Versioned<ExpenseHeader> expenseHeader) {
			this.expenseHeader = expenseHeader;
		}

		@Override
		public Versioned<ExpenseHeader> loadActiveAtVersion(ExpenseId expenseId, long version) {
			loaded = true;
			loadedExpenseId = expenseId;
			loadedAtVersion = version;
			return expenseHeader;
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

	private static final class FakeRecordingExpenseHeaderPort
			implements com.kartaguez.pocoma.engine.port.out.persistence.ExpenseHeaderPort {

		private Versioned<ExpenseHeader> previous;
		private Versioned<ExpenseHeader> next;

		@Override
		public void replace(Versioned<ExpenseHeader> previous, Versioned<ExpenseHeader> next) {
			this.previous = previous;
			this.next = next;
		}
	}

	private static final class FakeEventPublisherPort
			implements com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort {

		private ExpenseDeletedEvent published;

		@Override
		public void publish(ExpenseDeletedEvent event) {
			published = event;
		}
	}
}
