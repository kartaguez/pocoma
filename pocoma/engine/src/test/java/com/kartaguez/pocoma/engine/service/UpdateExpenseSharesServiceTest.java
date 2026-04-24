package com.kartaguez.pocoma.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.association.ExpenseShare;
import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.domain.policy.UpdateExpenseSharesAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.context.UpdateExpenseSharesContext;
import com.kartaguez.pocoma.engine.event.ExpenseSharesUpdatedEvent;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.model.Versioned;
import com.kartaguez.pocoma.engine.port.in.intent.UpdateExpenseSharesCommand;
import com.kartaguez.pocoma.engine.port.in.result.ExpenseSharesSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

class UpdateExpenseSharesServiceTest {

	@Test
	void updatesExpenseShares() {
		UpdateExpenseSharesFixture fixture = new UpdateExpenseSharesFixture();
		FakeExpenseContextPort loadContextPort =
				new FakeExpenseContextPort(fixture.context(false));
		FakeExpenseSharesPort loadExpenseSharesPort =
				new FakeExpenseSharesPort(fixture.versionedExpenseShares());
		FakePotGlobalVersionPort updatePotGlobalVersionPort = new FakePotGlobalVersionPort();
		FakeRecordingExpenseSharesPort replaceExpenseSharesPort = new FakeRecordingExpenseSharesPort();
		FakeEventPublisherPort publishEventPort = new FakeEventPublisherPort();
		UpdateExpenseSharesService service = new UpdateExpenseSharesService(
				loadContextPort,
				loadExpenseSharesPort,
				updatePotGlobalVersionPort,
				replaceExpenseSharesPort,
				publishEventPort,
				new UpdateExpenseSharesAuthorizationPolicy());

		ExpenseSharesSnapshot snapshot = service.updateExpenseShares(
				new UserContext(fixture.creatorId.value().toString()),
				fixture.command(3, fixture.bobId));

		assertEquals(fixture.expenseId, snapshot.expenseId());
		assertEquals(fixture.potId, snapshot.potId());
		assertEquals(4, snapshot.version());
		assertEquals(Set.of(fixture.bobId), snapshot.shares().keySet());
		assertEquals(Weight.of(Fraction.of(1, 1)), snapshot.shares().get(fixture.bobId).weight());
		assertEquals(fixture.expenseId, loadContextPort.loadedExpenseId);
		assertEquals(fixture.expenseId, loadExpenseSharesPort.loadedExpenseId);
		assertEquals(3, loadExpenseSharesPort.loadedAtVersion);
		assertEquals(new PotGlobalVersion(fixture.potId, 3), updatePotGlobalVersionPort.expectedActiveVersion);
		assertEquals(new PotGlobalVersion(fixture.potId, 4), updatePotGlobalVersionPort.nextVersion);
		assertEquals(Set.of(fixture.aliceId), replaceExpenseSharesPort.previous.value().shares().keySet());
		assertEquals(1, replaceExpenseSharesPort.previous.startedAtVersion());
		assertEquals(4L, replaceExpenseSharesPort.previous.endedAtVersion());
		assertEquals(Set.of(fixture.bobId), replaceExpenseSharesPort.next.value().shares().keySet());
		assertEquals(4, replaceExpenseSharesPort.next.startedAtVersion());
		assertNull(replaceExpenseSharesPort.next.endedAtVersion());
		assertEquals(new ExpenseSharesUpdatedEvent(fixture.expenseId, fixture.potId, 4), publishEventPort.published);
	}

	@Test
	void rejectsAlreadyDeletedExpenseWithoutLoadingFullExpenseShares() {
		UpdateExpenseSharesFixture fixture = new UpdateExpenseSharesFixture();
		FakeExpenseSharesPort loadExpenseSharesPort =
				new FakeExpenseSharesPort(fixture.versionedExpenseShares());
		UpdateExpenseSharesService service = fixture.service(fixture.context(true), loadExpenseSharesPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> service.updateExpenseShares(
						new UserContext(fixture.creatorId.value().toString()),
						fixture.command(3, fixture.bobId)));

		assertEquals("EXPENSE_ALREADY_DELETED", exception.ruleCode());
		assertFalse(loadExpenseSharesPort.loaded);
	}

	@Test
	void rejectsVersionConflictWithoutLoadingFullExpenseShares() {
		UpdateExpenseSharesFixture fixture = new UpdateExpenseSharesFixture();
		FakeExpenseSharesPort loadExpenseSharesPort =
				new FakeExpenseSharesPort(fixture.versionedExpenseShares());
		UpdateExpenseSharesService service = fixture.service(fixture.context(false), loadExpenseSharesPort);

		VersionConflictException exception = assertThrows(
				VersionConflictException.class,
				() -> service.updateExpenseShares(
						new UserContext(fixture.creatorId.value().toString()),
						fixture.command(2, fixture.bobId)));

		assertEquals("POT_VERSION_CONFLICT", exception.conflictCode());
		assertFalse(loadExpenseSharesPort.loaded);
	}

	@Test
	void rejectsUnknownShareholderWithoutLoadingFullExpenseShares() {
		UpdateExpenseSharesFixture fixture = new UpdateExpenseSharesFixture();
		FakeExpenseSharesPort loadExpenseSharesPort =
				new FakeExpenseSharesPort(fixture.versionedExpenseShares());
		UpdateExpenseSharesService service = fixture.service(fixture.context(false), loadExpenseSharesPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> service.updateExpenseShares(
						new UserContext(fixture.creatorId.value().toString()),
						fixture.command(3, ShareholderId.of(UUID.randomUUID()))));

		assertEquals("SHAREHOLDER_NOT_PRESENT", exception.ruleCode());
		assertFalse(loadExpenseSharesPort.loaded);
	}

	@Test
	void rejectsForbiddenUserWithoutLoadingFullExpenseShares() {
		UpdateExpenseSharesFixture fixture = new UpdateExpenseSharesFixture();
		FakeExpenseSharesPort loadExpenseSharesPort =
				new FakeExpenseSharesPort(fixture.versionedExpenseShares());
		UpdateExpenseSharesService service = fixture.service(fixture.context(false), loadExpenseSharesPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> service.updateExpenseShares(
						new UserContext(UUID.randomUUID().toString()),
						fixture.command(3, fixture.bobId)));

		assertEquals("EXPENSE_SHARES_UPDATE_FORBIDDEN", exception.ruleCode());
		assertFalse(loadExpenseSharesPort.loaded);
	}

	private static final class UpdateExpenseSharesFixture {
		private final PotId potId = PotId.of(UUID.randomUUID());
		private final ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		private final ShareholderId aliceId = ShareholderId.of(UUID.randomUUID());
		private final ShareholderId bobId = ShareholderId.of(UUID.randomUUID());
		private final UserId creatorId = UserId.of(UUID.randomUUID());

		private UpdateExpenseSharesContext context(boolean deleted) {
			return new UpdateExpenseSharesContext(
					new PotGlobalVersion(potId, 3),
					deleted,
					creatorId,
					Set.of(aliceId, bobId));
		}

		private Versioned<ExpenseShares> versionedExpenseShares() {
			return new Versioned<>(
					ExpenseShares.reconstitute(potId, Set.of(expenseShare(aliceId, Weight.of(Fraction.of(1, 1))))),
					1,
					null);
		}

		private UpdateExpenseSharesCommand command(long expectedVersion, ShareholderId shareholderId) {
			return new UpdateExpenseSharesCommand(
					expenseId.value(),
					Set.of(new UpdateExpenseSharesCommand.ExpenseShareInput(shareholderId.value(), 1, 1)),
					expectedVersion);
		}

		private ExpenseShare expenseShare(ShareholderId shareholderId, Weight weight) {
			return new ExpenseShare(expenseId, shareholderId, weight);
		}

		private UpdateExpenseSharesService service(
				UpdateExpenseSharesContext context,
				FakeExpenseSharesPort loadExpenseSharesPort) {
			return new UpdateExpenseSharesService(
					new FakeExpenseContextPort(context),
					loadExpenseSharesPort,
					new FakePotGlobalVersionPort(),
					new FakeRecordingExpenseSharesPort(),
					new FakeEventPublisherPort(),
					new UpdateExpenseSharesAuthorizationPolicy());
		}
	}

	private static final class FakeExpenseContextPort
			implements com.kartaguez.pocoma.engine.port.out.persistence.ExpenseContextPort {

		private final UpdateExpenseSharesContext context;
		private ExpenseId loadedExpenseId;

		private FakeExpenseContextPort(UpdateExpenseSharesContext context) {
			this.context = context;
		}

		@Override
		public UpdateExpenseSharesContext loadUpdateExpenseSharesContext(ExpenseId expenseId) {
			loadedExpenseId = expenseId;
			return context;
		}
	}

	private static final class FakeExpenseSharesPort
			implements com.kartaguez.pocoma.engine.port.out.persistence.ExpenseSharesPort {

		private final Versioned<ExpenseShares> expenseShares;
		private boolean loaded;
		private ExpenseId loadedExpenseId;
		private long loadedAtVersion;

		private FakeExpenseSharesPort(Versioned<ExpenseShares> expenseShares) {
			this.expenseShares = expenseShares;
		}

		@Override
		public Versioned<ExpenseShares> loadActiveAtVersion(ExpenseId expenseId, long version) {
			loaded = true;
			loadedExpenseId = expenseId;
			loadedAtVersion = version;
			return expenseShares;
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

	private static final class FakeRecordingExpenseSharesPort
			implements com.kartaguez.pocoma.engine.port.out.persistence.ExpenseSharesPort {

		private Versioned<ExpenseShares> previous;
		private Versioned<ExpenseShares> next;

		@Override
		public void replace(Versioned<ExpenseShares> previous, Versioned<ExpenseShares> next) {
			this.previous = previous;
			this.next = next;
		}
	}

	private static final class FakeEventPublisherPort
			implements com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort {

		private ExpenseSharesUpdatedEvent published;

		@Override
		public void publish(ExpenseSharesUpdatedEvent event) {
			published = event;
		}
	}
}
