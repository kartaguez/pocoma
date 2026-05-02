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
import com.kartaguez.pocoma.domain.policy.AddPotShareholdersAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Name;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.context.AddPotShareholdersContext;
import com.kartaguez.pocoma.engine.event.PotShareholdersAddedEvent;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.in.command.intent.AddPotShareholdersCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.PotShareholdersSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

class AddPotShareholdersServiceTest {

	@Test
	void addsPotShareholders() {
		AddPotShareholdersFixture fixture = new AddPotShareholdersFixture();
		FakePotContextPort loadAddPotShareholdersContextPort =
				new FakePotContextPort(fixture.context(false));
		FakePotShareholdersPort loadPotShareholdersPort =
				new FakePotShareholdersPort(fixture.potShareholders());
		FakePotGlobalVersionPort updatePotGlobalVersionPort = new FakePotGlobalVersionPort();
		FakeRecordingPotShareholdersPort replacePotShareholdersPort = new FakeRecordingPotShareholdersPort();
		FakeEventPublisherPort publishPotShareholdersAddedEventPort =
				new FakeEventPublisherPort();
		AddPotShareholdersService addPotShareholdersService = new AddPotShareholdersService(
				loadAddPotShareholdersContextPort,
				loadPotShareholdersPort,
				updatePotGlobalVersionPort,
				replacePotShareholdersPort,
				publishPotShareholdersAddedEventPort,
				new AddPotShareholdersAuthorizationPolicy());

		PotShareholdersSnapshot snapshot = addPotShareholdersService.addPotShareholders(
				new UserContext(fixture.creatorId.value().toString()),
				new AddPotShareholdersCommand(
						fixture.potId.value(),
						Set.of(
								new AddPotShareholdersCommand.ShareholderInput("Alice", 1, 2),
								new AddPotShareholdersCommand.ShareholderInput("Bob", 3, 4)),
						3));

		assertEquals(fixture.potId, snapshot.potId());
		assertEquals(3, snapshot.shareholders().size());
		assertEquals(4, snapshot.version());
		assertEquals(fixture.potId, loadAddPotShareholdersContextPort.loadedPotId);
		assertEquals(fixture.potId, loadPotShareholdersPort.loadedPotId);
		assertEquals(3, loadPotShareholdersPort.loadedAtVersion);
		assertEquals(new PotGlobalVersion(fixture.potId, 3), updatePotGlobalVersionPort.expectedActiveVersion);
		assertEquals(new PotGlobalVersion(fixture.potId, 4), updatePotGlobalVersionPort.nextVersion);
		assertEquals(3, replacePotShareholdersPort.saved.shareholders().size());
		assertEquals(new PotGlobalVersion(fixture.potId, 3), replacePotShareholdersPort.currentVersion);
		assertEquals(new PotGlobalVersion(fixture.potId, 4), replacePotShareholdersPort.nextVersion);
		assertEquals(2, publishPotShareholdersAddedEventPort.published.shareholderIds().size());
		assertEquals(fixture.potId, publishPotShareholdersAddedEventPort.published.potId());
		assertEquals(4, publishPotShareholdersAddedEventPort.published.version());
	}

	@Test
	void rejectsAlreadyDeletedPotWithoutLoadingFullPotShareholders() {
		AddPotShareholdersFixture fixture = new AddPotShareholdersFixture();
		FakePotShareholdersPort loadPotShareholdersPort =
				new FakePotShareholdersPort(fixture.potShareholders());
		AddPotShareholdersService addPotShareholdersService = fixture.service(fixture.context(true), loadPotShareholdersPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> addPotShareholdersService.addPotShareholders(
						new UserContext(fixture.creatorId.value().toString()),
						fixture.command(3)));

		assertEquals("POT_ALREADY_DELETED", exception.ruleCode());
		assertFalse(loadPotShareholdersPort.loaded);
	}

	@Test
	void rejectsVersionConflictWithoutLoadingFullPotShareholders() {
		AddPotShareholdersFixture fixture = new AddPotShareholdersFixture();
		FakePotShareholdersPort loadPotShareholdersPort =
				new FakePotShareholdersPort(fixture.potShareholders());
		AddPotShareholdersService addPotShareholdersService = fixture.service(fixture.context(false), loadPotShareholdersPort);

		VersionConflictException exception = assertThrows(
				VersionConflictException.class,
				() -> addPotShareholdersService.addPotShareholders(
						new UserContext(fixture.creatorId.value().toString()),
						fixture.command(2)));

		assertEquals("POT_VERSION_CONFLICT", exception.conflictCode());
		assertFalse(loadPotShareholdersPort.loaded);
	}

	@Test
	void rejectsForbiddenUserWithoutLoadingFullPotShareholders() {
		AddPotShareholdersFixture fixture = new AddPotShareholdersFixture();
		FakePotShareholdersPort loadPotShareholdersPort =
				new FakePotShareholdersPort(fixture.potShareholders());
		AddPotShareholdersService addPotShareholdersService = fixture.service(fixture.context(false), loadPotShareholdersPort);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> addPotShareholdersService.addPotShareholders(
						new UserContext(UUID.randomUUID().toString()),
						fixture.command(3)));

		assertEquals("POT_SHAREHOLDERS_ADD_FORBIDDEN", exception.ruleCode());
		assertFalse(loadPotShareholdersPort.loaded);
	}

	@Test
	void rejectsNullCommand() {
		AddPotShareholdersFixture fixture = new AddPotShareholdersFixture();
		AddPotShareholdersService addPotShareholdersService = fixture.service(fixture.context(false));

		assertThrows(NullPointerException.class, () -> addPotShareholdersService.addPotShareholders(
				new UserContext("user-id"),
				null));
	}

	@Test
	void rejectsNullUserContext() {
		AddPotShareholdersFixture fixture = new AddPotShareholdersFixture();
		AddPotShareholdersService addPotShareholdersService = fixture.service(fixture.context(false));

		assertThrows(NullPointerException.class, () -> addPotShareholdersService.addPotShareholders(
				null,
				fixture.command(3)));
	}

	@Test
	void rejectsNullLoadedContext() {
		AddPotShareholdersFixture fixture = new AddPotShareholdersFixture();
		AddPotShareholdersService addPotShareholdersService = fixture.service(null);

		assertThrows(NullPointerException.class, () -> addPotShareholdersService.addPotShareholders(
				new UserContext(fixture.creatorId.value().toString()),
				fixture.command(3)));
	}

	private static final class AddPotShareholdersFixture {
		private final PotId potId = PotId.of(UUID.randomUUID());
		private final UserId creatorId = UserId.of(UUID.randomUUID());
		private final Shareholder existingShareholder = Shareholder.reconstitute(
				ShareholderId.of(UUID.randomUUID()),
				potId,
				Name.of("Existing"),
				Weight.of(Fraction.of(1, 1)),
				null,
				false);

		private AddPotShareholdersContext context(boolean deleted) {
			return new AddPotShareholdersContext(
					new PotGlobalVersion(potId, 3),
					deleted,
					creatorId);
		}

		private PotShareholders potShareholders() {
			return PotShareholders.reconstitute(potId, Set.of(existingShareholder));
		}

		private AddPotShareholdersCommand command(long expectedVersion) {
			return new AddPotShareholdersCommand(
					potId.value(),
					Set.of(new AddPotShareholdersCommand.ShareholderInput("Alice", 1, 2)),
					expectedVersion);
		}

		private AddPotShareholdersService service(AddPotShareholdersContext context) {
			return service(context, new FakePotShareholdersPort(potShareholders()));
		}

		private AddPotShareholdersService service(
				AddPotShareholdersContext context,
				FakePotShareholdersPort loadPotShareholdersPort) {
			return new AddPotShareholdersService(
					new FakePotContextPort(context),
					loadPotShareholdersPort,
					new FakePotGlobalVersionPort(),
					new FakeRecordingPotShareholdersPort(),
					new FakeEventPublisherPort(),
					new AddPotShareholdersAuthorizationPolicy());
		}
	}

	private static final class FakePotContextPort
			implements com.kartaguez.pocoma.engine.port.out.persistence.PotContextPort {

		private final AddPotShareholdersContext context;
		private PotId loadedPotId;

		private FakePotContextPort(AddPotShareholdersContext context) {
			this.context = context;
		}

		@Override
		public AddPotShareholdersContext loadAddPotShareholdersContext(PotId potId) {
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

		private PotShareholdersAddedEvent published;

		@Override
		public void publish(PotShareholdersAddedEvent event) {
			published = event;
		}
	}
}
