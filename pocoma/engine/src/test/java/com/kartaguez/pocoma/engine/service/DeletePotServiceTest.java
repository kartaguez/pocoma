package com.kartaguez.pocoma.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.domain.policy.DeletePotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.context.DeletePotContext;
import com.kartaguez.pocoma.engine.event.PotDeletedEvent;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.model.Versioned;
import com.kartaguez.pocoma.engine.port.in.intent.DeletePotCommand;
import com.kartaguez.pocoma.engine.port.in.result.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

class DeletePotServiceTest {

	@Test
	void deletesPot() {
		DeletePotFixture fixture = new DeletePotFixture();
		FakePotContextPort loadDeletePotContextPort = new FakePotContextPort(fixture.context(false));
		FakePotHeaderPort loadPotHeaderPort = new FakePotHeaderPort(fixture.versionedPotHeader(false));
		FakePotGlobalVersionPort updatePotGlobalVersionPort = new FakePotGlobalVersionPort();
		FakeRecordingPotHeaderPort replacePotHeaderPort = new FakeRecordingPotHeaderPort();
		FakeEventPublisherPort publishPotDeletedEventPort = new FakeEventPublisherPort();
		DeletePotService deletePotService = new DeletePotService(
				loadDeletePotContextPort,
				loadPotHeaderPort,
				updatePotGlobalVersionPort,
				replacePotHeaderPort,
				publishPotDeletedEventPort,
				new DeletePotAuthorizationPolicy());

		PotHeaderSnapshot snapshot = deletePotService.deletePot(
				new UserContext(fixture.creatorId.value().toString()),
				new DeletePotCommand(fixture.potId.value(), 3));

		assertEquals(fixture.potId, snapshot.id());
		assertEquals(fixture.label, snapshot.label());
		assertEquals(fixture.creatorId, snapshot.creatorId());
		assertTrue(snapshot.deleted());
		assertEquals(4, snapshot.version());
		assertEquals(fixture.potId, loadDeletePotContextPort.loadedPotId);
		assertEquals(fixture.potId, loadPotHeaderPort.loadedPotId);
		assertEquals(3, loadPotHeaderPort.loadedAtVersion);
		assertEquals(new PotGlobalVersion(fixture.potId, 3), updatePotGlobalVersionPort.expectedActiveVersion);
		assertEquals(new PotGlobalVersion(fixture.potId, 4), updatePotGlobalVersionPort.nextVersion);
		assertFalse(replacePotHeaderPort.previous.value().deleted());
		assertEquals(1, replacePotHeaderPort.previous.startedAtVersion());
		assertEquals(4L, replacePotHeaderPort.previous.endedAtVersion());
		assertTrue(replacePotHeaderPort.next.value().deleted());
		assertEquals(4, replacePotHeaderPort.next.startedAtVersion());
		assertNull(replacePotHeaderPort.next.endedAtVersion());
		assertEquals(new PotDeletedEvent(fixture.potId, 4), publishPotDeletedEventPort.published);
	}

	@Test
	void rejectsAlreadyDeletedPotWithoutLoadingFullPotHeader() {
		DeletePotFixture fixture = new DeletePotFixture();
		FakePotHeaderPort loadPotHeaderPort = new FakePotHeaderPort(fixture.versionedPotHeader(false));
		DeletePotService deletePotService = fixture.service(fixture.context(true), loadPotHeaderPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> deletePotService.deletePot(
						new UserContext(fixture.creatorId.value().toString()),
						new DeletePotCommand(fixture.potId.value(), 3)));

		assertEquals("POT_ALREADY_DELETED", exception.ruleCode());
		assertFalse(loadPotHeaderPort.loaded);
	}

	@Test
	void rejectsVersionConflictWithoutLoadingFullPotHeader() {
		DeletePotFixture fixture = new DeletePotFixture();
		FakePotHeaderPort loadPotHeaderPort = new FakePotHeaderPort(fixture.versionedPotHeader(false));
		DeletePotService deletePotService = fixture.service(fixture.context(false), loadPotHeaderPort);

		VersionConflictException exception = assertThrows(
				VersionConflictException.class,
				() -> deletePotService.deletePot(
						new UserContext(fixture.creatorId.value().toString()),
						new DeletePotCommand(fixture.potId.value(), 2)));

		assertEquals("POT_VERSION_CONFLICT", exception.conflictCode());
		assertFalse(loadPotHeaderPort.loaded);
	}

	@Test
	void rejectsForbiddenUserWithoutLoadingFullPotHeader() {
		DeletePotFixture fixture = new DeletePotFixture();
		FakePotHeaderPort loadPotHeaderPort = new FakePotHeaderPort(fixture.versionedPotHeader(false));
		DeletePotService deletePotService = fixture.service(fixture.context(false), loadPotHeaderPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> deletePotService.deletePot(
						new UserContext(UUID.randomUUID().toString()),
						new DeletePotCommand(fixture.potId.value(), 3)));

		assertEquals("POT_DELETE_FORBIDDEN", exception.ruleCode());
		assertFalse(loadPotHeaderPort.loaded);
	}

	@Test
	void rejectsNullCommand() {
		DeletePotFixture fixture = new DeletePotFixture();
		DeletePotService deletePotService = fixture.service(fixture.context(false));

		assertThrows(NullPointerException.class, () -> deletePotService.deletePot(new UserContext("user-id"), null));
	}

	@Test
	void rejectsNullUserContext() {
		DeletePotFixture fixture = new DeletePotFixture();
		DeletePotService deletePotService = fixture.service(fixture.context(false));

		assertThrows(NullPointerException.class, () -> deletePotService.deletePot(
				null,
				new DeletePotCommand(fixture.potId.value(), 3)));
	}

	@Test
	void rejectsNullLoadedContext() {
		DeletePotFixture fixture = new DeletePotFixture();
		DeletePotService deletePotService = fixture.service(null);

		assertThrows(NullPointerException.class, () -> deletePotService.deletePot(
				new UserContext(fixture.creatorId.value().toString()),
				new DeletePotCommand(fixture.potId.value(), 3)));
	}

	private static final class DeletePotFixture {
		private final PotId potId = PotId.of(UUID.randomUUID());
		private final Label label = Label.of("Trip");
		private final UserId creatorId = UserId.of(UUID.randomUUID());

		private DeletePotContext context(boolean deleted) {
			return new DeletePotContext(
					new PotGlobalVersion(potId, 3),
					deleted,
					creatorId);
		}

		private Versioned<PotHeader> versionedPotHeader(boolean deleted) {
			return new Versioned<>(
					PotHeader.reconstitute(potId, label, creatorId, deleted),
					1,
					null);
		}

		private DeletePotService service(DeletePotContext context) {
			return service(context, new FakePotHeaderPort(versionedPotHeader(false)));
		}

		private DeletePotService service(DeletePotContext context, FakePotHeaderPort loadPotHeaderPort) {
			return new DeletePotService(
					new FakePotContextPort(context),
					loadPotHeaderPort,
					new FakePotGlobalVersionPort(),
					new FakeRecordingPotHeaderPort(),
					new FakeEventPublisherPort(),
					new DeletePotAuthorizationPolicy());
		}
	}

	private static final class FakePotContextPort
			implements com.kartaguez.pocoma.engine.port.out.persistence.PotContextPort {

		private final DeletePotContext context;
		private PotId loadedPotId;

		private FakePotContextPort(DeletePotContext context) {
			this.context = context;
		}

		@Override
		public DeletePotContext loadDeletePotContext(PotId potId) {
			loadedPotId = potId;
			return context;
		}
	}

	private static final class FakePotHeaderPort implements com.kartaguez.pocoma.engine.port.out.persistence.PotHeaderPort {

		private final Versioned<PotHeader> potHeader;
		private boolean loaded;
		private PotId loadedPotId;
		private long loadedAtVersion;

		private FakePotHeaderPort(Versioned<PotHeader> potHeader) {
			this.potHeader = potHeader;
		}

		@Override
		public Versioned<PotHeader> loadActiveAtVersion(PotId potId, long version) {
			loaded = true;
			loadedPotId = potId;
			loadedAtVersion = version;
			return potHeader;
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

	private static final class FakeRecordingPotHeaderPort
			implements com.kartaguez.pocoma.engine.port.out.persistence.PotHeaderPort {

		private Versioned<PotHeader> previous;
		private Versioned<PotHeader> next;

		@Override
		public void replace(Versioned<PotHeader> previous, Versioned<PotHeader> next) {
			this.previous = previous;
			this.next = next;
		}
	}

	private static final class FakeEventPublisherPort
			implements com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort {

		private PotDeletedEvent published;

		@Override
		public void publish(PotDeletedEvent event) {
			published = event;
		}
	}
}
