package com.kartaguez.pocoma.engine.write.pot.service;

import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.write.pot.context.UpdatePotDetailsContext;
import com.kartaguez.pocoma.domain.pot.event.PotDetailsUpdatedEvent;
import com.kartaguez.pocoma.domain.pot.version.PotGlobalVersion;
import com.kartaguez.pocoma.engine.write.pot.input.UpdatePotDetailsInput;
import com.kartaguez.pocoma.engine.write.pot.snapshot.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.write.pot.security.UserContext;

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
				new PotAuthorizationGuard());

		PotHeaderSnapshot snapshot = updatePotDetailsService.updatePotDetails(
				new UserContext(fixture.creatorId, fixture.userPermissions),
				new UpdatePotDetailsInput(fixture.potId.value(), "New trip", 3));

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
	void preservesObservationWhileLoadingAndMutatingAtTheWorkingVersion() {
		var fixture = new UpdatePotDetailsFixture();
		var context = new UpdatePotDetailsContext(new PotGlobalVersion(fixture.potId, 3), false, fixture.creatorId, 1);
		var load = new FakePotHeaderPort(fixture.potHeader(false));
		var versions = new FakePotGlobalVersionPort();
		var writer = new FakeRecordingPotHeaderPort();
		var events = new FakeEventPublisherPort();
		var command = new UpdatePotDetailsInput(fixture.potId.value(), "Compatible old observation", 2);
		var service = new UpdatePotDetailsService(new FakePotContextPort(context), load, versions, writer, events, new PotAuthorizationGuard());
		var result = service.updatePotDetails(new UserContext(fixture.creatorId, fixture.userPermissions), command);
		assertEquals(2, command.observedVersion());
		assertEquals(3, load.loadedAtVersion);
		assertEquals(3, versions.expectedActiveVersion.version());
		assertEquals(4, result.version());
		assertEquals(new PotDetailsUpdatedEvent(fixture.potId, 4), events.published);
	}

	@Test
	void rejectsAlreadyDeletedPotWithoutLoadingFullPotHeader() {
		UpdatePotDetailsFixture fixture = new UpdatePotDetailsFixture();
		FakePotHeaderPort loadPotHeaderPort = new FakePotHeaderPort(fixture.potHeader(false));
		UpdatePotDetailsService updatePotDetailsService = fixture.service(fixture.context(true), loadPotHeaderPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> updatePotDetailsService.updatePotDetails(
						new UserContext(fixture.creatorId, fixture.userPermissions),
						new UpdatePotDetailsInput(fixture.potId.value(), "New trip", 3)));

		assertEquals("POT_ALREADY_DELETED", exception.ruleCode());
		assertFalse(loadPotHeaderPort.loaded);
	}

	@Test
	void rejectsObsoleteCommandWithoutLoadingFullPotHeader() {
		UpdatePotDetailsFixture fixture = new UpdatePotDetailsFixture();
		FakePotHeaderPort loadPotHeaderPort = new FakePotHeaderPort(fixture.potHeader(false));
		UpdatePotDetailsService updatePotDetailsService = fixture.service(fixture.context(false), loadPotHeaderPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> updatePotDetailsService.updatePotDetails(
						new UserContext(fixture.creatorId, fixture.userPermissions),
						new UpdatePotDetailsInput(fixture.potId.value(), "New trip", 2)));

		assertEquals("OBSOLETE_COMMAND", exception.ruleCode());
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
						new UserContext(UserId.of(UUID.randomUUID()), fixture.userPermissions),
						new UpdatePotDetailsInput(fixture.potId.value(), "New trip", 3)));

		assertEquals("POT_DETAILS_UPDATE_FORBIDDEN", exception.ruleCode());
		assertFalse(loadPotHeaderPort.loaded);
	}

	@Test
	void rejectsNullCommand() {
		UpdatePotDetailsFixture fixture = new UpdatePotDetailsFixture();
		UpdatePotDetailsService updatePotDetailsService = fixture.service(fixture.context(false));

		assertThrows(NullPointerException.class, () -> updatePotDetailsService.updatePotDetails(
				new UserContext(fixture.creatorId, fixture.userPermissions),
				null));
	}

	@Test
	void rejectsNullUserContext() {
		UpdatePotDetailsFixture fixture = new UpdatePotDetailsFixture();
		UpdatePotDetailsService updatePotDetailsService = fixture.service(fixture.context(false));

		assertThrows(NullPointerException.class, () -> updatePotDetailsService.updatePotDetails(
				null,
				new UpdatePotDetailsInput(fixture.potId.value(), "New trip", 3)));
	}

	@Test
	void rejectsNullLoadedContext() {
		UpdatePotDetailsFixture fixture = new UpdatePotDetailsFixture();
		UpdatePotDetailsService updatePotDetailsService = fixture.service(null);

		assertThrows(NullPointerException.class, () -> updatePotDetailsService.updatePotDetails(
				new UserContext(fixture.creatorId, fixture.userPermissions),
				new UpdatePotDetailsInput(fixture.potId.value(), "New trip", 3)));
	}

	private static final class UpdatePotDetailsFixture {
		private final PotId potId = PotId.of(UUID.randomUUID());
		private final Label label = Label.of("Trip");
		private final UserId creatorId = UserId.of(UUID.randomUUID());
		private final Set<Permission> userPermissions = Set.of(new Permission("POT", "UPDATE"));

		private UpdatePotDetailsContext context(boolean deleted) {
			return new UpdatePotDetailsContext(new PotGlobalVersion(potId, 3), deleted, creatorId, 3L);
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
					new PotAuthorizationGuard());
		}
	}

	private static final class FakePotContextPort
			implements com.kartaguez.pocoma.engine.write.pot.port.persistence.PotContextPort {

		private final UpdatePotDetailsContext context;
		private PotId loadedPotId;

		private FakePotContextPort(UpdatePotDetailsContext context) {
			this.context = context;
		}

		@Override
		public Optional<UpdatePotDetailsContext> loadUpdatePotDetailsContext(PotId potId) {
			loadedPotId = potId;
			return Optional.of(context);
		}
	}

	private static final class FakePotHeaderPort implements com.kartaguez.pocoma.engine.write.pot.port.persistence.PotHeaderPort {

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
			implements com.kartaguez.pocoma.engine.write.pot.port.persistence.PotGlobalVersionPort {

		private PotGlobalVersion expectedActiveVersion;
		private PotGlobalVersion nextVersion;

		@Override
		public void updateIfActive(PotGlobalVersion expectedActiveVersion, PotGlobalVersion nextVersion) {
			this.expectedActiveVersion = expectedActiveVersion;
			this.nextVersion = nextVersion;
		}
	}

	private static final class FakeRecordingPotHeaderPort
			implements com.kartaguez.pocoma.engine.write.pot.port.persistence.PotHeaderPort {

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
			implements com.kartaguez.pocoma.engine.write.pot.port.event.EventPublisherPort {

		private PotDetailsUpdatedEvent published;

		@Override
		public void publish(PotDetailsUpdatedEvent event) {
			published = event;
		}
	}
}
