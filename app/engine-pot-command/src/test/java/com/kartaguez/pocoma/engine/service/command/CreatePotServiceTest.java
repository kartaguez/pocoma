package com.kartaguez.pocoma.engine.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.policy.CreatePotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.engine.pot.version.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreatePotCommand;
import com.kartaguez.pocoma.engine.snapshot.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

class CreatePotServiceTest {

	private static final Set<Permission> CREATE_POT_PERMISSIONS = Set.of(new Permission("POT", "CREATE"));

	@Test
	void createsPotAtInitialVersion() {
		FakePotGlobalVersionPort savePotGlobalVersionPort = new FakePotGlobalVersionPort();
		FakePotHeaderPort savePotHeaderPort = new FakePotHeaderPort();
		FakeEventPublisherPort publishPotCreatedEventPort = new FakeEventPublisherPort();
		CreatePotService createPotService = new CreatePotService(
				savePotGlobalVersionPort,
				savePotHeaderPort,
				publishPotCreatedEventPort,
				new CreatePotAuthorizationPolicy());
		Label label = Label.of("Trip");
		UserId creatorId = UserId.of(UUID.randomUUID());

		PotHeaderSnapshot snapshot = createPotService.createPot(
				new UserContext(creatorId, CREATE_POT_PERMISSIONS),
				new CreatePotCommand(label.value(), creatorId.value()));

		assertEquals(label, snapshot.label());
		assertEquals(creatorId, snapshot.creatorId());
		assertFalse(snapshot.deleted());
		assertEquals(1, snapshot.version());
		assertEquals(new PotGlobalVersion(snapshot.id(), 1), savePotGlobalVersionPort.saved);
		assertEquals(1, savePotHeaderPort.savedVersion);
		assertEquals(snapshot.id(), savePotHeaderPort.saved.id());
		assertEquals(label, savePotHeaderPort.saved.label());
		assertEquals(creatorId, savePotHeaderPort.saved.creatorId());
		assertFalse(savePotHeaderPort.saved.deleted());
		assertEquals(new PotCreatedEvent(snapshot.id(), 1), publishPotCreatedEventPort.published);
	}

	@Test
	void rejectsNullCommand() {
		CreatePotService createPotService = new CreatePotService(
				new FakePotGlobalVersionPort(),
				new FakePotHeaderPort(),
				new FakeEventPublisherPort(),
				new CreatePotAuthorizationPolicy());

		assertThrows(NullPointerException.class, () -> createPotService.createPot(
				new UserContext(UserId.of(UUID.randomUUID()), CREATE_POT_PERMISSIONS),
				null));
	}

	@Test
	void rejectsNullUserContext() {
		CreatePotService createPotService = new CreatePotService(
				new FakePotGlobalVersionPort(),
				new FakePotHeaderPort(),
				new FakeEventPublisherPort(),
				new CreatePotAuthorizationPolicy());

		assertThrows(NullPointerException.class, () -> createPotService.createPot(
				null,
				new CreatePotCommand("Trip", UUID.randomUUID())));
	}

	@Test
	void rejectsAnonymousUser() {
		CreatePotService createPotService = new CreatePotService(
				new FakePotGlobalVersionPort(),
				new FakePotHeaderPort(),
				new FakeEventPublisherPort(),
				new CreatePotAuthorizationPolicy());

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> createPotService.createPot(
						new UserContext(null, CREATE_POT_PERMISSIONS),
						new CreatePotCommand("Trip", UUID.randomUUID())));

		assertEquals("ANONYMOUS_USER", exception.ruleCode());
	}

	@Test
	void rejectsNullLabel() {
		assertThrows(NullPointerException.class, () -> new CreatePotCommand(null, UUID.randomUUID()));
	}

	@Test
	void rejectsNullCreatorId() {
		assertThrows(NullPointerException.class, () -> new CreatePotCommand("Trip", null));
	}

	@Test
	void rejectsNullPotGlobalVersionPort() {
		assertThrows(NullPointerException.class, () -> new CreatePotService(
				null,
				new FakePotHeaderPort(),
				new FakeEventPublisherPort(),
				new CreatePotAuthorizationPolicy()));
	}

	@Test
	void rejectsNullPotHeaderPort() {
		assertThrows(NullPointerException.class, () -> new CreatePotService(
				new FakePotGlobalVersionPort(),
				null,
				new FakeEventPublisherPort(),
				new CreatePotAuthorizationPolicy()));
	}

	@Test
	void rejectsNullEventPublisherPort() {
		assertThrows(NullPointerException.class, () -> new CreatePotService(
				new FakePotGlobalVersionPort(),
				new FakePotHeaderPort(),
				null,
				new CreatePotAuthorizationPolicy()));
	}

	@Test
	void rejectsNullCreatePotAuthorizationPolicy() {
		assertThrows(NullPointerException.class, () -> new CreatePotService(
				new FakePotGlobalVersionPort(),
				new FakePotHeaderPort(),
				new FakeEventPublisherPort(),
				null));
	}

	private static final class FakePotGlobalVersionPort
			implements com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort {

		private PotGlobalVersion saved;

		@Override
		public void save(PotGlobalVersion potGlobalVersion) {
			saved = potGlobalVersion;
		}
	}

	private static final class FakePotHeaderPort implements com.kartaguez.pocoma.engine.port.out.persistence.PotHeaderPort {

		private PotHeader saved;
		private long savedVersion;

		@Override
		public void saveNew(PotHeader potHeader, long version) {
			saved = potHeader;
			savedVersion = version;
		}
	}

	private static final class FakeEventPublisherPort
			implements com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort {

		private PotCreatedEvent published;

		@Override
		public void publish(PotCreatedEvent event) {
			published = event;
		}
	}
}
