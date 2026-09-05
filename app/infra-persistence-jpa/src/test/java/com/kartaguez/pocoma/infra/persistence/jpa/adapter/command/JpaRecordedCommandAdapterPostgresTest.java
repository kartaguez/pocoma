package com.kartaguez.pocoma.infra.persistence.jpa.adapter.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.engine.command.discovery.CommandConsumptionCandidate;
import com.kartaguez.pocoma.engine.command.discovery.CommandDiscoveryCursor;
import com.kartaguez.pocoma.engine.command.model.AuthorizationSnapshot;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.model.CommandType;
import com.kartaguez.pocoma.engine.command.model.PocomaUserId;
import com.kartaguez.pocoma.engine.command.model.RecordedCommand;
import com.kartaguez.pocoma.engine.command.port.out.RecordedCommandAlreadyExistsException;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.command.JpaCommandConsumptionDiscoveryRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.command.JpaRecordedCommandRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.identity.JpaExternalIdentityResolverAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.identity.ExternalIdentityJdbcRepository;
import com.kartaguez.pocoma.orchestrator.command.admission.model.ExternalIdentity;

@SpringBootTest(properties = {
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.flyway.enabled=true",
		"spring.flyway.locations=classpath:db/migration"
})
@Testcontainers
class JpaRecordedCommandAdapterPostgresTest {
	private static final Instant NOW = Instant.parse("2026-09-05T08:00:00Z");

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired private JpaRecordedCommandAdapter commands;
	@Autowired private JpaExternalIdentityResolverAdapter identities;
	@Autowired private JpaCommandConsumptionDiscoveryAdapter discovery;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformTransactionManager transactionManager;
	private ExecutorService executor;

	@BeforeEach
	void cleanDatabase() {
		jdbc.update("update consumption_slots set current_claim_id = null");
		jdbc.update("delete from consumption_claims");
		jdbc.update("delete from consumption_slots");
		jdbc.update("delete from recorded_commands");
		jdbc.update("delete from external_identities");
		executor = Executors.newFixedThreadPool(2);
	}

	@Test
	void resolvesProvisionedExternalIdentitiesExactlyInsideTheCallingTransaction() {
		UUID userId = uuid(200);
		jdbc.update("insert into external_identities (issuer,subject,pocoma_user_id) values (?,?,?)",
				"https://issuer.example", "subject-1", userId);

		assertEquals(new PocomaUserId(userId), inTransaction(() -> identities.findUserId(
				new ExternalIdentity("https://issuer.example", "subject-1")).orElseThrow()));
		assertTrue(inTransaction(() -> identities.findUserId(
				new ExternalIdentity("https://issuer.example", "subject-2"))).isEmpty());
		assertThrows(IllegalTransactionStateException.class, () -> identities.findUserId(
				new ExternalIdentity("https://issuer.example", "subject-1")));
	}

	@Test
	void permitsSeveralExternalIdentitiesForOneUserButKeepsEachExternalKeyUnique() {
		UUID userId = uuid(201);
		jdbc.update("insert into external_identities (issuer,subject,pocoma_user_id) values (?,?,?)",
				"https://issuer-a.example", "subject-a", userId);
		jdbc.update("insert into external_identities (issuer,subject,pocoma_user_id) values (?,?,?)",
				"https://issuer-b.example", "subject-b", userId);

		assertEquals(new PocomaUserId(userId), inTransaction(() -> identities.findUserId(
				new ExternalIdentity("https://issuer-a.example", "subject-a")).orElseThrow()));
		assertEquals(new PocomaUserId(userId), inTransaction(() -> identities.findUserId(
				new ExternalIdentity("https://issuer-b.example", "subject-b")).orElseThrow()));
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
				"insert into external_identities (issuer,subject,pocoma_user_id) values (?,?,?)",
				"https://issuer-a.example", "subject-a", uuid(202)));
	}

	@AfterEach
	void stopExecutor() {
		executor.shutdownNow();
	}

	@Test
	void insertsAndReloadsTheCompleteImmutableEnvelope() {
		String payload = "{ \"z\": 2, \"a\": [1, true] }";
		Set<Permission> permissions = Set.of(
				new Permission("POT", "UPDATE"), new Permission("FUTURE_FEATURE", "VIEW"));
		RecordedCommand expected = command(uuid(1), NOW, payload, permissions);

		inTransaction(() -> { commands.insert(expected); return null; });
		RecordedCommand reloaded = inTransaction(() -> commands.findById(expected.commandId()).orElseThrow());

		assertEquals(expected, reloaded);
		assertEquals(payload, reloaded.serializedPayload());
		assertEquals(permissions, reloaded.authorization().permissions());
	}

	@Test
	void preservesEmptyAndNonJsonPayloadsWithoutParsingThem() {
		RecordedCommand empty = command(uuid(1), NOW, "", Set.of());
		RecordedCommand invalidJson = command(uuid(2), NOW.plusSeconds(1), "not-json", Set.of());

		inTransaction(() -> { commands.insert(empty); commands.insert(invalidJson); return null; });

		assertEquals("", inTransaction(() -> commands.findById(empty.commandId()).orElseThrow()).serializedPayload());
		assertEquals("not-json",
				inTransaction(() -> commands.findById(invalidJson.commandId()).orElseThrow()).serializedPayload());
	}

	@Test
	void duplicateIdFailsExplicitlyAndNeverChangesTheOriginal() {
		RecordedCommand original = command(uuid(1), NOW, "first", Set.of());
		RecordedCommand duplicate = command(uuid(1), NOW.plusSeconds(1), "second",
				Set.of(new Permission("POT", "DELETE")));
		inTransaction(() -> { commands.insert(original); return null; });

		assertThrows(RecordedCommandAlreadyExistsException.class,
				() -> inTransaction(() -> { commands.insert(duplicate); return null; }));

		assertEquals(original, inTransaction(() -> commands.findById(original.commandId()).orElseThrow()));
	}

	@Test
	void recordingRequiresAndJoinsTheCallingTransaction() {
		RecordedCommand command = command(uuid(1), NOW, "payload", Set.of());
		assertThrows(IllegalTransactionStateException.class, () -> commands.insert(command));
		assertThrows(IllegalTransactionStateException.class, () -> commands.findById(command.commandId()));

		assertThrows(RollbackMarker.class, () -> inTransaction(() -> {
			commands.insert(command);
			throw new RollbackMarker();
		}));
		assertTrue(inTransaction(() -> commands.findById(command.commandId())).isEmpty());
	}

	@Test
	void insertCreatesNoConsumptionLifecycleState() {
		inTransaction(() -> { commands.insert(command(uuid(1), NOW, "payload", Set.of())); return null; });

		assertEquals(0, jdbc.queryForObject("select count(*) from consumption_slots", Integer.class));
		assertEquals(0, jdbc.queryForObject("select count(*) from consumption_claims", Integer.class));
	}

	@Test
	void migrationEnforcesOnlyStructuralPayloadAndAuthorizationConstraints() {
		RecordedCommand command = command(uuid(1), NOW, "", Set.of());
		inTransaction(() -> { commands.insert(command); return null; });
		assertEquals("", jdbc.queryForObject(
				"select payload_json from recorded_commands where command_id=?", String.class, uuid(1)));

		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
				insert into recorded_commands
				(command_id,command_type,payload_json,submitted_at,auth_user_id,auth_issuer,
				 auth_authenticated_at,auth_issued_at,auth_valid_until,auth_permissions_json)
				values (?,' ', '', ?, ?, 'issuer', ?, ?, ?, '[]'::jsonb)
				""", uuid(2), Timestamp.from(NOW), uuid(20), Timestamp.from(NOW), Timestamp.from(NOW),
				Timestamp.from(NOW.plusSeconds(1))));
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
				insert into recorded_commands
				(command_id,command_type,payload_json,submitted_at,auth_user_id,auth_issuer,
				 auth_authenticated_at,auth_issued_at,auth_valid_until,auth_permissions_json)
				values (?,'TYPE', '', ?, ?, 'issuer', ?, ?, ?, '{}'::jsonb)
				""", uuid(3), Timestamp.from(NOW), uuid(30), Timestamp.from(NOW), Timestamp.from(NOW),
				Timestamp.from(NOW.plusSeconds(1))));
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
				insert into recorded_commands
				(command_id,command_type,payload_json,submitted_at,auth_user_id,auth_issuer,
				 auth_authenticated_at,auth_issued_at,auth_valid_until,auth_permissions_json)
				values (?,'TYPE', null, ?, ?, 'issuer', ?, ?, ?, '[]'::jsonb)
				""", uuid(4), Timestamp.from(NOW), uuid(40), Timestamp.from(NOW), Timestamp.from(NOW),
				Timestamp.from(NOW.plusSeconds(1))));
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
				insert into recorded_commands
				(command_id,command_type,payload_json,submitted_at,auth_user_id,auth_issuer,
				 auth_authenticated_at,auth_issued_at,auth_valid_until,auth_permissions_json)
				values (?,'TYPE', '', ?, ?, ' ', ?, ?, ?, '[]'::jsonb)
				""", uuid(5), Timestamp.from(NOW), uuid(50), Timestamp.from(NOW), Timestamp.from(NOW),
				Timestamp.from(NOW.plusSeconds(1))));
	}

	@Test
	void discoveryFiltersOnlyObviouslyIneligibleLifecycleStates() {
		RecordedCommand noSlot = command(uuid(1), NOW, "one", Set.of());
		RecordedCommand done = command(uuid(2), NOW.plusSeconds(1), "two", Set.of());
		RecordedCommand futureRetry = command(uuid(3), NOW.plusSeconds(2), "three", Set.of());
		RecordedCommand activeClaim = command(uuid(4), NOW.plusSeconds(3), "four", Set.of());
		RecordedCommand expiredClaim = command(uuid(5), NOW.plusSeconds(4), "five", Set.of());
		inTransaction(() -> {
			for (RecordedCommand command : List.of(noSlot, done, futureRetry, activeClaim, expiredClaim)) {
				commands.insert(command);
			}
			return null;
		});
		insertDoneSlot(done.commandId());
		insertPendingSlot(futureRetry.commandId(), NOW.plusSeconds(60));
		insertClaimedSlot(activeClaim.commandId(), NOW.plusSeconds(30));
		insertClaimedSlot(expiredClaim.commandId(), NOW.minusSeconds(1));

		List<UUID> discovered = discoverAll(NOW);

		assertEquals(List.of(noSlot.commandId().value(), expiredClaim.commandId().value()), discovered);
	}

	@Test
	void postgresDefinesStableKeysetOrderWithoutDuplicatesOrGaps() {
		Instant tie = NOW.minusSeconds(1);
		List<RecordedCommand> inserted = List.of(
				command(uuid(30), tie, "thirty", Set.of()),
				command(uuid(2), NOW.minusSeconds(2), "two", Set.of()),
				command(uuid(20), tie, "twenty", Set.of()),
				command(uuid(10), tie, "ten", Set.of()),
				command(uuid(40), NOW, "forty", Set.of()));
		inTransaction(() -> { inserted.forEach(commands::insert); return null; });
		List<UUID> postgresOrder = jdbc.queryForList(
				"select command_id from recorded_commands order by submitted_at, command_id", UUID.class);

		List<UUID> discovered = discoverAll(NOW.plusSeconds(1));

		assertEquals(postgresOrder, discovered);
		assertEquals(inserted.size(), new HashSet<>(discovered).size());
		assertEquals(inserted.stream().map(value -> value.commandId().value()).collect(java.util.stream.Collectors.toSet()),
				Set.copyOf(discovered));
	}

	@Test
	void concurrentDiscoveriesMayReturnTheSameCandidate() throws Exception {
		RecordedCommand command = command(uuid(1), NOW.minusSeconds(1), "payload", Set.of());
		inTransaction(() -> { commands.insert(command); return null; });
		CountDownLatch start = new CountDownLatch(1);
		Future<UUID> first = executor.submit(() -> discoverAfter(start));
		Future<UUID> second = executor.submit(() -> discoverAfter(start));
		start.countDown();

		assertEquals(command.commandId().value(), first.get());
		assertEquals(command.commandId().value(), second.get());
		assertEquals(0, jdbc.queryForObject("select count(*) from consumption_slots", Integer.class));
	}

	private UUID discoverAfter(CountDownLatch start) throws InterruptedException {
		start.await();
		return discovery.findNextEligibleCandidate(NOW, Optional.empty()).orElseThrow().commandId().value();
	}

	private List<UUID> discoverAll(Instant now) {
		List<UUID> result = new ArrayList<>();
		Optional<CommandDiscoveryCursor> cursor = Optional.empty();
		while (true) {
			Optional<CommandConsumptionCandidate> candidate = discovery.findNextEligibleCandidate(now, cursor);
			if (candidate.isEmpty()) return List.copyOf(result);
			result.add(candidate.orElseThrow().commandId().value());
			cursor = Optional.of(candidate.orElseThrow().cursor());
		}
	}

	private void insertDoneSlot(CommandId commandId) {
		UUID slotId = UUID.randomUUID();
		jdbc.update("""
				insert into consumption_slots
				(slot_id,consumable_type,consumable_components,consumer_type,consumer_components,
				 status,terminal_outcome,next_claim_at,created_at,done_at)
				values (?,'COMMAND',jsonb_build_array(?::text),'COMMAND_PROCESSOR','[]'::jsonb,
				 'DONE','SUCCESS',?,?,?)
				""", slotId, commandId.value().toString(), Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
	}

	private void insertPendingSlot(CommandId commandId, Instant nextClaimAt) {
		jdbc.update("""
				insert into consumption_slots
				(slot_id,consumable_type,consumable_components,consumer_type,consumer_components,
				 status,current_claim_id,next_claim_at,created_at)
				values (?,'COMMAND',jsonb_build_array(?::text),'COMMAND_PROCESSOR','[]'::jsonb,
				 'PENDING',null,?,?)
				""", UUID.randomUUID(), commandId.value().toString(), Timestamp.from(nextClaimAt),
				Timestamp.from(NOW.minusSeconds(60)));
	}

	private void insertClaimedSlot(CommandId commandId, Instant leaseUntil) {
		UUID slotId = UUID.randomUUID();
		UUID claimId = UUID.randomUUID();
		insertPendingSlotWithId(slotId, commandId);
		Instant claimedAt = NOW.minusSeconds(60);
		jdbc.update("""
				insert into consumption_claims
				(claim_id,slot_id,attempt_number,claimed_by,claimed_at,lease_until)
				values (?,?,1,'worker',?,?)
				""", claimId, slotId, Timestamp.from(claimedAt), Timestamp.from(leaseUntil));
		jdbc.update("update consumption_slots set current_claim_id=? where slot_id=?", claimId, slotId);
	}

	private void insertPendingSlotWithId(UUID slotId, CommandId commandId) {
		jdbc.update("""
				insert into consumption_slots
				(slot_id,consumable_type,consumable_components,consumer_type,consumer_components,
				 status,next_claim_at,created_at)
				values (?,'COMMAND',jsonb_build_array(?::text),'COMMAND_PROCESSOR','[]'::jsonb,
				 'PENDING',?,?)
				""", slotId, commandId.value().toString(), Timestamp.from(NOW), Timestamp.from(NOW.minusSeconds(60)));
	}

	private <T> T inTransaction(Supplier<T> action) {
		return new TransactionTemplate(transactionManager).execute(status -> action.get());
	}

	private static RecordedCommand command(UUID id, Instant submittedAt, String payload,
			Set<Permission> permissions) {
		AuthorizationSnapshot authorization = new AuthorizationSnapshot(new PocomaUserId(uuid(100)), permissions,
				NOW.minusSeconds(30), NOW.minusSeconds(20), NOW.plusSeconds(300), "pocoma-auth");
		return new RecordedCommand(new CommandId(id), new CommandType("POT_CREATE_V1"),
				payload, submittedAt, authorization);
	}

	private static UUID uuid(int value) {
		return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", value));
	}

	private static final class RollbackMarker extends RuntimeException {}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import({JpaRecordedCommandAdapter.class, JpaCommandConsumptionDiscoveryAdapter.class,
			JpaExternalIdentityResolverAdapter.class, ExternalIdentityJdbcRepository.class,
			JpaRecordedCommandRepository.class, JpaCommandConsumptionDiscoveryRepository.class})
	static class TestApplication {
		@Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
	}
}
