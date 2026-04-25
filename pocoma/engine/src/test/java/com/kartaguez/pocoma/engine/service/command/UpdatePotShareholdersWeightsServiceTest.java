package com.kartaguez.pocoma.engine.service.command;

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
import com.kartaguez.pocoma.domain.policy.UpdatePotShareholdersWeightsAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Name;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.context.UpdatePotShareholdersWeightsContext;
import com.kartaguez.pocoma.engine.event.PotShareholdersWeightsUpdatedEvent;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotShareholdersWeightsCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.PotShareholdersSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

class UpdatePotShareholdersWeightsServiceTest {

	@Test
	void updatesPotShareholdersWeights() {
		UpdatePotShareholdersWeightsFixture fixture = new UpdatePotShareholdersWeightsFixture();
		FakePotContextPort loadContextPort =
				new FakePotContextPort(fixture.context(false));
		FakePotShareholdersPort loadPotShareholdersPort =
				new FakePotShareholdersPort(fixture.potShareholders());
		FakePotGlobalVersionPort updatePotGlobalVersionPort = new FakePotGlobalVersionPort();
		FakeRecordingPotShareholdersPort replacePotShareholdersPort = new FakeRecordingPotShareholdersPort();
		FakeEventPublisherPort publishEventPort =
				new FakeEventPublisherPort();
		UpdatePotShareholdersWeightsService service = new UpdatePotShareholdersWeightsService(
				loadContextPort,
				loadPotShareholdersPort,
				updatePotGlobalVersionPort,
				replacePotShareholdersPort,
				publishEventPort,
				new UpdatePotShareholdersWeightsAuthorizationPolicy());

		PotShareholdersSnapshot snapshot = service.updatePotShareholdersWeights(
				new UserContext(fixture.creatorId.value().toString()),
				new UpdatePotShareholdersWeightsCommand(
						fixture.potId.value(),
						Set.of(new UpdatePotShareholdersWeightsCommand.ShareholderWeightInput(
								fixture.shareholderId.value(),
								3,
								4)),
						3));

		Shareholder updatedShareholder = snapshot.shareholders().stream()
				.filter(shareholder -> shareholder.id().equals(fixture.shareholderId))
				.findFirst()
				.orElseThrow();
		assertEquals(Weight.of(Fraction.of(3, 4)), updatedShareholder.weight());
		assertEquals(4, snapshot.version());
		assertEquals(fixture.potId, loadContextPort.loadedPotId);
		assertEquals(fixture.potId, loadPotShareholdersPort.loadedPotId);
		assertEquals(3, loadPotShareholdersPort.loadedAtVersion);
		assertEquals(new PotGlobalVersion(fixture.potId, 3), updatePotGlobalVersionPort.expectedActiveVersion);
		assertEquals(new PotGlobalVersion(fixture.potId, 4), updatePotGlobalVersionPort.nextVersion);
		assertEquals(1, replacePotShareholdersPort.saved.shareholders().size());
		assertEquals(new PotGlobalVersion(fixture.potId, 3), replacePotShareholdersPort.currentVersion);
		assertEquals(new PotGlobalVersion(fixture.potId, 4), replacePotShareholdersPort.nextVersion);
		assertEquals(new PotShareholdersWeightsUpdatedEvent(fixture.potId, Set.of(fixture.shareholderId), 4), publishEventPort.published);
	}

	@Test
	void rejectsUnknownShareholderId() {
		UpdatePotShareholdersWeightsFixture fixture = new UpdatePotShareholdersWeightsFixture();
		UpdatePotShareholdersWeightsService service = fixture.service(fixture.context(false));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> service.updatePotShareholdersWeights(
						new UserContext(fixture.creatorId.value().toString()),
						fixture.command(ShareholderId.of(UUID.randomUUID()), 3)));

		assertEquals("SHAREHOLDER_NOT_PRESENT", exception.ruleCode());
	}

	@Test
	void rejectsAlreadyDeletedPotWithoutLoadingFullPotShareholders() {
		UpdatePotShareholdersWeightsFixture fixture = new UpdatePotShareholdersWeightsFixture();
		FakePotShareholdersPort loadPotShareholdersPort =
				new FakePotShareholdersPort(fixture.potShareholders());
		UpdatePotShareholdersWeightsService service = fixture.service(fixture.context(true), loadPotShareholdersPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> service.updatePotShareholdersWeights(
						new UserContext(fixture.creatorId.value().toString()),
						fixture.command(fixture.shareholderId, 3)));

		assertEquals("POT_ALREADY_DELETED", exception.ruleCode());
		assertFalse(loadPotShareholdersPort.loaded);
	}

	@Test
	void rejectsVersionConflictWithoutLoadingFullPotShareholders() {
		UpdatePotShareholdersWeightsFixture fixture = new UpdatePotShareholdersWeightsFixture();
		FakePotShareholdersPort loadPotShareholdersPort =
				new FakePotShareholdersPort(fixture.potShareholders());
		UpdatePotShareholdersWeightsService service = fixture.service(fixture.context(false), loadPotShareholdersPort);

		VersionConflictException exception = assertThrows(
				VersionConflictException.class,
				() -> service.updatePotShareholdersWeights(
						new UserContext(fixture.creatorId.value().toString()),
						fixture.command(fixture.shareholderId, 2)));

		assertEquals("POT_VERSION_CONFLICT", exception.conflictCode());
		assertFalse(loadPotShareholdersPort.loaded);
	}

	@Test
	void rejectsForbiddenUserWithoutLoadingFullPotShareholders() {
		UpdatePotShareholdersWeightsFixture fixture = new UpdatePotShareholdersWeightsFixture();
		FakePotShareholdersPort loadPotShareholdersPort =
				new FakePotShareholdersPort(fixture.potShareholders());
		UpdatePotShareholdersWeightsService service = fixture.service(fixture.context(false), loadPotShareholdersPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> service.updatePotShareholdersWeights(
						new UserContext(UUID.randomUUID().toString()),
						fixture.command(fixture.shareholderId, 3)));

		assertEquals("POT_SHAREHOLDERS_WEIGHTS_UPDATE_FORBIDDEN", exception.ruleCode());
		assertFalse(loadPotShareholdersPort.loaded);
	}

	private static final class UpdatePotShareholdersWeightsFixture {
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

		private UpdatePotShareholdersWeightsContext context(boolean deleted) {
			return new UpdatePotShareholdersWeightsContext(
					new PotGlobalVersion(potId, 3),
					deleted,
					creatorId,
					Set.of(shareholderId));
		}

		private PotShareholders potShareholders() {
			return PotShareholders.reconstitute(potId, Set.of(shareholder));
		}

		private UpdatePotShareholdersWeightsCommand command(ShareholderId shareholderId, long expectedVersion) {
			return new UpdatePotShareholdersWeightsCommand(
					potId.value(),
					Set.of(new UpdatePotShareholdersWeightsCommand.ShareholderWeightInput(
							shareholderId.value(),
							3,
							4)),
					expectedVersion);
		}

		private UpdatePotShareholdersWeightsService service(UpdatePotShareholdersWeightsContext context) {
			return service(context, new FakePotShareholdersPort(potShareholders()));
		}

		private UpdatePotShareholdersWeightsService service(
				UpdatePotShareholdersWeightsContext context,
				FakePotShareholdersPort loadPotShareholdersPort) {
			return new UpdatePotShareholdersWeightsService(
					new FakePotContextPort(context),
					loadPotShareholdersPort,
					new FakePotGlobalVersionPort(),
					new FakeRecordingPotShareholdersPort(),
					new FakeEventPublisherPort(),
					new UpdatePotShareholdersWeightsAuthorizationPolicy());
		}
	}

	private static final class FakePotContextPort
			implements com.kartaguez.pocoma.engine.port.out.persistence.PotContextPort {

		private final UpdatePotShareholdersWeightsContext context;
		private PotId loadedPotId;

		private FakePotContextPort(UpdatePotShareholdersWeightsContext context) {
			this.context = context;
		}

		@Override
		public UpdatePotShareholdersWeightsContext loadUpdatePotShareholdersWeightsContext(PotId potId) {
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

		private PotShareholdersWeightsUpdatedEvent published;

		@Override
		public void publish(PotShareholdersWeightsUpdatedEvent event) {
			published = event;
		}
	}
}
