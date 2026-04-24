package com.kartaguez.pocoma.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.domain.policy.UpdateExpenseDetailsAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.Amount;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.context.UpdateExpenseDetailsContext;
import com.kartaguez.pocoma.engine.event.ExpenseDetailsUpdatedEvent;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.model.Versioned;
import com.kartaguez.pocoma.engine.port.in.intent.UpdateExpenseDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.result.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

class UpdateExpenseDetailsServiceTest {

	@Test
	void updatesExpenseDetails() {
		UpdateExpenseDetailsFixture fixture = new UpdateExpenseDetailsFixture();
		FakeExpenseContextPort loadContextPort =
				new FakeExpenseContextPort(fixture.context(false));
		FakeExpenseHeaderPort loadExpenseHeaderPort =
				new FakeExpenseHeaderPort(fixture.versionedExpenseHeader(false));
		FakePotGlobalVersionPort updatePotGlobalVersionPort = new FakePotGlobalVersionPort();
		FakeRecordingExpenseHeaderPort replaceExpenseHeaderPort = new FakeRecordingExpenseHeaderPort();
		FakeEventPublisherPort publishEventPort = new FakeEventPublisherPort();
		UpdateExpenseDetailsService service = new UpdateExpenseDetailsService(
				loadContextPort,
				loadExpenseHeaderPort,
				updatePotGlobalVersionPort,
				replaceExpenseHeaderPort,
				publishEventPort,
				new UpdateExpenseDetailsAuthorizationPolicy());

		ExpenseHeaderSnapshot snapshot = service.updateExpenseDetails(
				new UserContext(fixture.creatorId.value().toString()),
				fixture.command(3, fixture.nextPayerId));

		assertEquals(fixture.expenseId, snapshot.id());
		assertEquals(fixture.potId, snapshot.potId());
		assertEquals(fixture.nextPayerId, snapshot.payerId());
		assertEquals(Amount.of(Fraction.of(84, 1)), snapshot.amount());
		assertEquals(Label.of("Updated dinner"), snapshot.label());
		assertFalse(snapshot.deleted());
		assertEquals(4, snapshot.version());
		assertEquals(fixture.expenseId, loadContextPort.loadedExpenseId);
		assertEquals(fixture.expenseId, loadExpenseHeaderPort.loadedExpenseId);
		assertEquals(3, loadExpenseHeaderPort.loadedAtVersion);
		assertEquals(new PotGlobalVersion(fixture.potId, 3), updatePotGlobalVersionPort.expectedActiveVersion);
		assertEquals(new PotGlobalVersion(fixture.potId, 4), updatePotGlobalVersionPort.nextVersion);
		assertEquals(fixture.payerId, replaceExpenseHeaderPort.previous.value().payerId());
		assertEquals(1, replaceExpenseHeaderPort.previous.startedAtVersion());
		assertEquals(4L, replaceExpenseHeaderPort.previous.endedAtVersion());
		assertEquals(fixture.nextPayerId, replaceExpenseHeaderPort.next.value().payerId());
		assertEquals(4, replaceExpenseHeaderPort.next.startedAtVersion());
		assertNull(replaceExpenseHeaderPort.next.endedAtVersion());
		assertEquals(new ExpenseDetailsUpdatedEvent(fixture.expenseId, fixture.potId, 4), publishEventPort.published);
	}

	@Test
	void rejectsAlreadyDeletedExpenseWithoutLoadingFullExpenseHeader() {
		UpdateExpenseDetailsFixture fixture = new UpdateExpenseDetailsFixture();
		FakeExpenseHeaderPort loadExpenseHeaderPort =
				new FakeExpenseHeaderPort(fixture.versionedExpenseHeader(false));
		UpdateExpenseDetailsService service = fixture.service(fixture.context(true), loadExpenseHeaderPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> service.updateExpenseDetails(
						new UserContext(fixture.creatorId.value().toString()),
						fixture.command(3, fixture.nextPayerId)));

		assertEquals("EXPENSE_ALREADY_DELETED", exception.ruleCode());
		assertFalse(loadExpenseHeaderPort.loaded);
	}

	@Test
	void rejectsVersionConflictWithoutLoadingFullExpenseHeader() {
		UpdateExpenseDetailsFixture fixture = new UpdateExpenseDetailsFixture();
		FakeExpenseHeaderPort loadExpenseHeaderPort =
				new FakeExpenseHeaderPort(fixture.versionedExpenseHeader(false));
		UpdateExpenseDetailsService service = fixture.service(fixture.context(false), loadExpenseHeaderPort);

		VersionConflictException exception = assertThrows(
				VersionConflictException.class,
				() -> service.updateExpenseDetails(
						new UserContext(fixture.creatorId.value().toString()),
						fixture.command(2, fixture.nextPayerId)));

		assertEquals("POT_VERSION_CONFLICT", exception.conflictCode());
		assertFalse(loadExpenseHeaderPort.loaded);
	}

	@Test
	void rejectsUnknownPayerWithoutLoadingFullExpenseHeader() {
		UpdateExpenseDetailsFixture fixture = new UpdateExpenseDetailsFixture();
		FakeExpenseHeaderPort loadExpenseHeaderPort =
				new FakeExpenseHeaderPort(fixture.versionedExpenseHeader(false));
		UpdateExpenseDetailsService service = fixture.service(fixture.context(false), loadExpenseHeaderPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> service.updateExpenseDetails(
						new UserContext(fixture.creatorId.value().toString()),
						fixture.command(3, ShareholderId.of(UUID.randomUUID()))));

		assertEquals("SHAREHOLDER_NOT_PRESENT", exception.ruleCode());
		assertFalse(loadExpenseHeaderPort.loaded);
	}

	@Test
	void rejectsForbiddenUserWithoutLoadingFullExpenseHeader() {
		UpdateExpenseDetailsFixture fixture = new UpdateExpenseDetailsFixture();
		FakeExpenseHeaderPort loadExpenseHeaderPort =
				new FakeExpenseHeaderPort(fixture.versionedExpenseHeader(false));
		UpdateExpenseDetailsService service = fixture.service(fixture.context(false), loadExpenseHeaderPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> service.updateExpenseDetails(
						new UserContext(UUID.randomUUID().toString()),
						fixture.command(3, fixture.nextPayerId)));

		assertEquals("EXPENSE_DETAILS_UPDATE_FORBIDDEN", exception.ruleCode());
		assertFalse(loadExpenseHeaderPort.loaded);
	}

	private static final class UpdateExpenseDetailsFixture {
		private final PotId potId = PotId.of(UUID.randomUUID());
		private final ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		private final ShareholderId payerId = ShareholderId.of(UUID.randomUUID());
		private final ShareholderId nextPayerId = ShareholderId.of(UUID.randomUUID());
		private final UserId creatorId = UserId.of(UUID.randomUUID());
		private final Amount amount = Amount.of(Fraction.of(42, 1));
		private final Label label = Label.of("Dinner");

		private UpdateExpenseDetailsContext context(boolean deleted) {
			return new UpdateExpenseDetailsContext(
					new PotGlobalVersion(potId, 3),
					deleted,
					creatorId,
					Set.of(payerId, nextPayerId));
		}

		private Versioned<ExpenseHeader> versionedExpenseHeader(boolean deleted) {
			return new Versioned<>(
					ExpenseHeader.reconstitute(expenseId, potId, payerId, amount, label, deleted),
					1,
					null);
		}

		private UpdateExpenseDetailsCommand command(long expectedVersion, ShareholderId payerId) {
			return new UpdateExpenseDetailsCommand(
					expenseId.value(),
					payerId.value(),
					84,
					1,
					"Updated dinner",
					expectedVersion);
		}

		private UpdateExpenseDetailsService service(
				UpdateExpenseDetailsContext context,
				FakeExpenseHeaderPort loadExpenseHeaderPort) {
			return new UpdateExpenseDetailsService(
					new FakeExpenseContextPort(context),
					loadExpenseHeaderPort,
					new FakePotGlobalVersionPort(),
					new FakeRecordingExpenseHeaderPort(),
					new FakeEventPublisherPort(),
					new UpdateExpenseDetailsAuthorizationPolicy());
		}
	}

	private static final class FakeExpenseContextPort
			implements com.kartaguez.pocoma.engine.port.out.persistence.ExpenseContextPort {

		private final UpdateExpenseDetailsContext context;
		private ExpenseId loadedExpenseId;

		private FakeExpenseContextPort(UpdateExpenseDetailsContext context) {
			this.context = context;
		}

		@Override
		public UpdateExpenseDetailsContext loadUpdateExpenseDetailsContext(ExpenseId expenseId) {
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

		private ExpenseDetailsUpdatedEvent published;

		@Override
		public void publish(ExpenseDetailsUpdatedEvent event) {
			published = event;
		}
	}
}
