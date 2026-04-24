package com.kartaguez.pocoma.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.entity.Shareholder;
import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.domain.policy.UpdatePotShareholdersDetailsAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Name;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.context.UpdatePotShareholdersDetailsContext;
import com.kartaguez.pocoma.engine.event.PotShareholdersDetailsUpdatedEvent;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.in.intent.UpdatePotShareholdersDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.result.PotShareholdersSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

class UpdatePotShareholdersDetailsServiceTest {

	@Test
	void updatesPotShareholdersDetails() {
		UpdatePotShareholdersDetailsFixture fixture = new UpdatePotShareholdersDetailsFixture();
		FakePotContextPort loadContextPort =
				new FakePotContextPort(fixture.context(false));
		FakePotShareholdersPort loadPotShareholdersPort =
				new FakePotShareholdersPort(fixture.potShareholders());
		FakePotGlobalVersionPort updatePotGlobalVersionPort = new FakePotGlobalVersionPort();
		FakeRecordingPotShareholdersPort replacePotShareholdersPort = new FakeRecordingPotShareholdersPort();
		FakeEventPublisherPort publishEventPort =
				new FakeEventPublisherPort();
		UpdatePotShareholdersDetailsService service = new UpdatePotShareholdersDetailsService(
				loadContextPort,
				loadPotShareholdersPort,
				updatePotGlobalVersionPort,
				replacePotShareholdersPort,
				publishEventPort,
				new UpdatePotShareholdersDetailsAuthorizationPolicy());
		UUID linkedUserId = UUID.randomUUID();

		PotShareholdersSnapshot snapshot = service.updatePotShareholdersDetails(
				new UserContext(fixture.creatorId.value().toString()),
				new UpdatePotShareholdersDetailsCommand(
						fixture.potId.value(),
						Set.of(new UpdatePotShareholdersDetailsCommand.ShareholderDetailsInput(
								fixture.shareholderId.value(),
								"Alice Updated",
								linkedUserId)),
						3));

		Shareholder updatedShareholder = snapshot.shareholders().stream()
				.filter(shareholder -> shareholder.id().equals(fixture.shareholderId))
				.findFirst()
				.orElseThrow();
		assertEquals(Name.of("Alice Updated"), updatedShareholder.name());
		assertEquals(UserId.of(linkedUserId), updatedShareholder.userId());
		assertEquals(4, snapshot.version());
		assertEquals(fixture.potId, loadContextPort.loadedPotId);
		assertEquals(fixture.potId, loadPotShareholdersPort.loadedPotId);
		assertEquals(3, loadPotShareholdersPort.loadedAtVersion);
		assertEquals(new PotGlobalVersion(fixture.potId, 3), updatePotGlobalVersionPort.expectedActiveVersion);
		assertEquals(new PotGlobalVersion(fixture.potId, 4), updatePotGlobalVersionPort.nextVersion);
		assertEquals(1, replacePotShareholdersPort.saved.shareholders().size());
		assertEquals(new PotGlobalVersion(fixture.potId, 3), replacePotShareholdersPort.currentVersion);
		assertEquals(new PotGlobalVersion(fixture.potId, 4), replacePotShareholdersPort.nextVersion);
		assertEquals(new PotShareholdersDetailsUpdatedEvent(fixture.potId, Set.of(fixture.shareholderId), 4), publishEventPort.published);
	}

	@Test
	void rejectsUnknownShareholderId() {
		UpdatePotShareholdersDetailsFixture fixture = new UpdatePotShareholdersDetailsFixture();
		UpdatePotShareholdersDetailsService service = fixture.service(fixture.context(false));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> service.updatePotShareholdersDetails(
						new UserContext(fixture.creatorId.value().toString()),
						fixture.command(ShareholderId.of(UUID.randomUUID()), 3)));

		assertEquals("SHAREHOLDER_NOT_PRESENT", exception.ruleCode());
	}

	@Test
	void rejectsAlreadyDeletedPotWithoutLoadingFullPotShareholders() {
		UpdatePotShareholdersDetailsFixture fixture = new UpdatePotShareholdersDetailsFixture();
		FakePotShareholdersPort loadPotShareholdersPort =
				new FakePotShareholdersPort(fixture.potShareholders());
		UpdatePotShareholdersDetailsService service = fixture.service(fixture.context(true), loadPotShareholdersPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> service.updatePotShareholdersDetails(
						new UserContext(fixture.creatorId.value().toString()),
						fixture.command(fixture.shareholderId, 3)));

		assertEquals("POT_ALREADY_DELETED", exception.ruleCode());
		assertFalse(loadPotShareholdersPort.loaded);
	}

	@Test
	void rejectsVersionConflictWithoutLoadingFullPotShareholders() {
		UpdatePotShareholdersDetailsFixture fixture = new UpdatePotShareholdersDetailsFixture();
		FakePotShareholdersPort loadPotShareholdersPort =
				new FakePotShareholdersPort(fixture.potShareholders());
		UpdatePotShareholdersDetailsService service = fixture.service(fixture.context(false), loadPotShareholdersPort);

		VersionConflictException exception = assertThrows(
				VersionConflictException.class,
				() -> service.updatePotShareholdersDetails(
						new UserContext(fixture.creatorId.value().toString()),
						fixture.command(fixture.shareholderId, 2)));

		assertEquals("POT_VERSION_CONFLICT", exception.conflictCode());
		assertFalse(loadPotShareholdersPort.loaded);
	}

	@Test
	void rejectsForbiddenUserWithoutLoadingFullPotShareholders() {
		UpdatePotShareholdersDetailsFixture fixture = new UpdatePotShareholdersDetailsFixture();
		FakePotShareholdersPort loadPotShareholdersPort =
				new FakePotShareholdersPort(fixture.potShareholders());
		UpdatePotShareholdersDetailsService service = fixture.service(fixture.context(false), loadPotShareholdersPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> service.updatePotShareholdersDetails(
						new UserContext(UUID.randomUUID().toString()),
						fixture.command(fixture.shareholderId, 3)));

		assertEquals("POT_SHAREHOLDERS_DETAILS_UPDATE_FORBIDDEN", exception.ruleCode());
		assertFalse(loadPotShareholdersPort.loaded);
	}

	private static final class UpdatePotShareholdersDetailsFixture {
		private final PotId potId = PotId.of(UUID.randomUUID());
		private final UserId creatorId = UserId.of(UUID.randomUUID());
		private final ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
		private final Shareholder shareholder = Shareholder.reconstitute(
				shareholderId,
				potId,
				Name.of("Alice"),
				Weight.of(Fraction.of(1, 1)),
				null,
				false);

		private UpdatePotShareholdersDetailsContext context(boolean deleted) {
			return new UpdatePotShareholdersDetailsContext(
					new PotGlobalVersion(potId, 3),
					deleted,
					creatorId,
					Set.of(shareholderId));
		}

		private PotShareholders potShareholders() {
			return PotShareholders.reconstitute(potId, Set.of(shareholder));
		}

		private UpdatePotShareholdersDetailsCommand command(ShareholderId shareholderId, long expectedVersion) {
			return new UpdatePotShareholdersDetailsCommand(
					potId.value(),
					Set.of(new UpdatePotShareholdersDetailsCommand.ShareholderDetailsInput(
							shareholderId.value(),
							"Alice Updated",
							null)),
					expectedVersion);
		}

		private UpdatePotShareholdersDetailsService service(UpdatePotShareholdersDetailsContext context) {
			return service(context, new FakePotShareholdersPort(potShareholders()));
		}

		private UpdatePotShareholdersDetailsService service(
				UpdatePotShareholdersDetailsContext context,
				FakePotShareholdersPort loadPotShareholdersPort) {
			return new UpdatePotShareholdersDetailsService(
					new FakePotContextPort(context),
					loadPotShareholdersPort,
					new FakePotGlobalVersionPort(),
					new FakeRecordingPotShareholdersPort(),
					new FakeEventPublisherPort(),
					new UpdatePotShareholdersDetailsAuthorizationPolicy());
		}
	}

	private static final class FakePotContextPort
			implements com.kartaguez.pocoma.engine.port.out.persistence.PotContextPort {

		private final UpdatePotShareholdersDetailsContext context;
		private PotId loadedPotId;

		private FakePotContextPort(UpdatePotShareholdersDetailsContext context) {
			this.context = context;
		}

		@Override
		public UpdatePotShareholdersDetailsContext loadUpdatePotShareholdersDetailsContext(PotId potId) {
			loadedPotId = potId;
			return context;
		}
	}

	private static final class FakePotShareholdersPort
			implements com.kartaguez.pocoma.engine.port.out.persistence.PotShareholdersPort {

		private final PotShareholders potShareholders;
		private boolean loaded;
		private PotId loadedPotId;
		private long loadedAtVersion;

		private FakePotShareholdersPort(PotShareholders potShareholders) {
			this.potShareholders = potShareholders;
		}

		@Override
		public PotShareholders loadActiveAtVersion(PotId potId, long version) {
			loaded = true;
			loadedPotId = potId;
			loadedAtVersion = version;
			return potShareholders;
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

	private static final class FakeRecordingPotShareholdersPort
			implements com.kartaguez.pocoma.engine.port.out.persistence.PotShareholdersPort {

		private PotShareholders saved;
		private PotGlobalVersion currentVersion;
		private PotGlobalVersion nextVersion;

		@Override
		public void save(PotShareholders potShareholders, PotGlobalVersion currentVersion, PotGlobalVersion nextVersion) {
			this.saved = potShareholders;
			this.currentVersion = currentVersion;
			this.nextVersion = nextVersion;
		}
	}

	private static final class FakeEventPublisherPort
			implements com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort {

		private PotShareholdersDetailsUpdatedEvent published;

		@Override
		public void publish(PotShareholdersDetailsUpdatedEvent event) {
			published = event;
		}
	}
}
