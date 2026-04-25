package com.kartaguez.pocoma.engine.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.domain.policy.UpdatePotDetailsAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.context.UpdatePotDetailsContext;
import com.kartaguez.pocoma.engine.event.PotDetailsUpdatedEvent;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

class UpdatePotDetailsServiceTest {

	@Test
	void updatesPotDetails() {
		UpdatePotDetailsFixture fixture = new UpdatePotDetailsFixture();
		FakePotContextPort loadUpdatePotDetailsContextPort =
				new FakePotContextPort(fixture.context(false));
		FakePotHeaderPort loadPotHeaderPort = new FakePotHeaderPort(fixture.potHeader(false));
		FakePotGlobalVersionPort updatePotGlobalVersionPort = new FakePotGlobalVersionPort();
		FakeRecordingPotHeaderPort replacePotHeaderPort = new FakeRecordingPotHeaderPort();
		FakeEventPublisherPort publishPotDetailsUpdatedEventPort =
				new FakeEventPublisherPort();
		UpdatePotDetailsService updatePotDetailsService = new UpdatePotDetailsService(
				loadUpdatePotDetailsContextPort,
				loadPotHeaderPort,
				updatePotGlobalVersionPort,
				replacePotHeaderPort,
				publishPotDetailsUpdatedEventPort,
				new UpdatePotDetailsAuthorizationPolicy());

		PotHeaderSnapshot snapshot = updatePotDetailsService.updatePotDetails(
				new UserContext(fixture.creatorId.value().toString()),
				new UpdatePotDetailsCommand(fixture.potId.value(), "New trip", 3));

		assertEquals(fixture.potId, snapshot.id());
		assertEquals(Label.of("New trip"), snapshot.label());
		assertEquals(fixture.creatorId, snapshot.creatorId());
		assertFalse(snapshot.deleted());
		assertEquals(4, snapshot.version());
		assertEquals(fixture.potId, loadUpdatePotDetailsContextPort.loadedPotId);
		assertEquals(fixture.potId, loadPotHeaderPort.loadedPotId);
		assertEquals(3, loadPotHeaderPort.loadedAtVersion);
		assertEquals(new PotGlobalVersion(fixture.potId, 3), updatePotGlobalVersionPort.expectedActiveVersion);
		assertEquals(new PotGlobalVersion(fixture.potId, 4), updatePotGlobalVersionPort.nextVersion);
		assertEquals(Label.of("New trip"), replacePotHeaderPort.saved.label());
		assertEquals(new PotGlobalVersion(fixture.potId, 3), replacePotHeaderPort.currentVersion);
		assertEquals(new PotGlobalVersion(fixture.potId, 4), replacePotHeaderPort.nextVersion);
		assertEquals(new PotDetailsUpdatedEvent(fixture.potId, 4), publishPotDetailsUpdatedEventPort.published);
	}

	@Test
	void rejectsAlreadyDeletedPotWithoutLoadingFullPotHeader() {
		UpdatePotDetailsFixture fixture = new UpdatePotDetailsFixture();
		FakePotHeaderPort loadPotHeaderPort = new FakePotHeaderPort(fixture.potHeader(false));
		UpdatePotDetailsService updatePotDetailsService = fixture.service(fixture.context(true), loadPotHeaderPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> updatePotDetailsService.updatePotDetails(
						new UserContext(fixture.creatorId.value().toString()),
						new UpdatePotDetailsCommand(fixture.potId.value(), "New trip", 3)));

		assertEquals("POT_ALREADY_DELETED", exception.ruleCode());
		assertFalse(loadPotHeaderPort.loaded);
	}

	@Test
	void rejectsVersionConflictWithoutLoadingFullPotHeader() {
		UpdatePotDetailsFixture fixture = new UpdatePotDetailsFixture();
		FakePotHeaderPort loadPotHeaderPort = new FakePotHeaderPort(fixture.potHeader(false));
		UpdatePotDetailsService updatePotDetailsService = fixture.service(fixture.context(false), loadPotHeaderPort);

		VersionConflictException exception = assertThrows(
				VersionConflictException.class,
				() -> updatePotDetailsService.updatePotDetails(
						new UserContext(fixture.creatorId.value().toString()),
						new UpdatePotDetailsCommand(fixture.potId.value(), "New trip", 2)));

		assertEquals("POT_VERSION_CONFLICT", exception.conflictCode());
		assertFalse(loadPotHeaderPort.loaded);
	}

	@Test
	void rejectsForbiddenUserWithoutLoadingFullPotHeader() {
		UpdatePotDetailsFixture fixture = new UpdatePotDetailsFixture();
		FakePotHeaderPort loadPotHeaderPort = new FakePotHeaderPort(fixture.potHeader(false));
		UpdatePotDetailsService updatePotDetailsService = fixture.service(fixture.context(false), loadPotHeaderPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> updatePotDetailsService.updatePotDetails(
						new UserContext(UUID.randomUUID().toString()),
						new UpdatePotDetailsCommand(fixture.potId.value(), "New trip", 3)));

		assertEquals("POT_DETAILS_UPDATE_FORBIDDEN", exception.ruleCode());
		assertFalse(loadPotHeaderPort.loaded);
	}

	@Test
	void rejectsNullCommand() {
		UpdatePotDetailsFixture fixture = new UpdatePotDetailsFixture();
		UpdatePotDetailsService updatePotDetailsService = fixture.service(fixture.context(false));

		assertThrows(NullPointerException.class, () -> updatePotDetailsService.updatePotDetails(
				new UserContext("user-id"),
				null));
	}

	@Test
	void rejectsNullUserContext() {
		UpdatePotDetailsFixture fixture = new UpdatePotDetailsFixture();
		UpdatePotDetailsService updatePotDetailsService = fixture.service(fixture.context(false));

		assertThrows(NullPointerException.class, () -> updatePotDetailsService.updatePotDetails(
				null,
				new UpdatePotDetailsCommand(fixture.potId.value(), "New trip", 3)));
	}

	@Test
	void rejectsNullLoadedContext() {
		UpdatePotDetailsFixture fixture = new UpdatePotDetailsFixture();
		UpdatePotDetailsService updatePotDetailsService = fixture.service(null);

		assertThrows(NullPointerException.class, () -> updatePotDetailsService.updatePotDetails(
				new UserContext(fixture.creatorId.value().toString()),
				new UpdatePotDetailsCommand(fixture.potId.value(), "New trip", 3)));
	}

	private static final class UpdatePotDetailsFixture {
		private final PotId potId = PotId.of(UUID.randomUUID());
		private final Label label = Label.of("Trip");
		private final UserId creatorId = UserId.of(UUID.randomUUID());

		private UpdatePotDetailsContext context(boolean deleted) {
			return new UpdatePotDetailsContext(
					new PotGlobalVersion(potId, 3),
					deleted,
					creatorId);
		}

		private PotHeader potHeader(boolean deleted) {
			return PotHeader.reconstitute(potId, label, creatorId, deleted);
		}

		private UpdatePotDetailsService service(UpdatePotDetailsContext context) {
			return service(context, new FakePotHeaderPort(potHeader(false)));
		}

		private UpdatePotDetailsService service(
				UpdatePotDetailsContext context,
				FakePotHeaderPort loadPotHeaderPort) {
			return new UpdatePotDetailsService(
					new FakePotContextPort(context),
					loadPotHeaderPort,
					new FakePotGlobalVersionPort(),
					new FakeRecordingPotHeaderPort(),
					new FakeEventPublisherPort(),
					new UpdatePotDetailsAuthorizationPolicy());
		}
	}

	private static final class FakePotContextPort
			implements com.kartaguez.pocoma.engine.port.out.persistence.PotContextPort {

		private final UpdatePotDetailsContext context;
		private PotId loadedPotId;

		private FakePotContextPort(UpdatePotDetailsContext context) {
			this.context = context;
		}

		@Override
		public UpdatePotDetailsContext loadUpdatePotDetailsContext(PotId potId) {
			loadedPotId = potId;
			return context;
		}
	}

	private static final class FakePotHeaderPort implements com.kartaguez.pocoma.engine.port.out.persistence.PotHeaderPort {

		private final PotHeader potHeader;
		private boolean loaded;
		private PotId loadedPotId;
		private long loadedAtVersion;

		private FakePotHeaderPort(PotHeader potHeader) {
			this.potHeader = potHeader;
		}

		@Override
		public PotHeader loadActiveAtVersion(PotId potId, long version) {
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

		private PotHeader saved;
		private PotGlobalVersion currentVersion;
		private PotGlobalVersion nextVersion;

		@Override
		public void save(PotHeader potHeader, PotGlobalVersion currentVersion, PotGlobalVersion nextVersion) {
			this.saved = potHeader;
			this.currentVersion = currentVersion;
			this.nextVersion = nextVersion;
		}
	}

	private static final class FakeEventPublisherPort
			implements com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort {

		private PotDetailsUpdatedEvent published;

		@Override
		public void publish(PotDetailsUpdatedEvent event) {
			published = event;
		}
	}
}
