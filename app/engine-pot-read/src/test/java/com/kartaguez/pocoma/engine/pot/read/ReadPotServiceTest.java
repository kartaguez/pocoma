package com.kartaguez.pocoma.engine.pot.read;

import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.POT_VIEW;
import static com.kartaguez.pocoma.engine.pot.read.ProjectionFixtures.POT_UUID;
import static com.kartaguez.pocoma.engine.pot.read.ProjectionFixtures.SHAREHOLDER_A_UUID;
import static com.kartaguez.pocoma.engine.pot.read.ProjectionFixtures.USER_UUID;
import static com.kartaguez.pocoma.engine.pot.read.ProjectionFixtures.VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.authorization.TokenCapabilities;
import com.kartaguez.pocoma.domain.projection.pot.definition.AuthProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.pot.definition.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.ProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.engine.read.projection.port.ExactProjectionReadUseCase;
import com.kartaguez.pocoma.engine.read.projection.port.ProjectionReadResult;

class ReadPotServiceTest {
	private static final PotId POT_ID = new PotId(POT_UUID);
	private static final UserId USER_ID = new UserId(USER_UUID);
	private static final TokenCapabilities VIEW = TokenCapabilities.of(POT_VIEW);

	@Test
	void readsAuthThenTheExactReadPotForTheCreator() {
		var reader = new RecordingReader(
				readyAuth(USER_UUID, Map.of()),
				new ProjectionReadResult.Ready(
						ProjectionFixtures.readPotProjection(List.of(ProjectionFixtures.pot()))));

		var ready = assertInstanceOf(ReadPotResult.Ready.class,
				PotReads.create(reader).read(USER_ID, VIEW, POT_ID, VERSION));

		assertEquals(POT_ID, ready.pot().potId());
		assertEquals(VERSION, ready.pot().version());
		assertEquals(List.of(ProjectionFixtures.authKey(), ProjectionFixtures.readPotKey()), reader.keys);
		assertEquals(List.of(AuthProjectionDefinition.DEFINITION, ReadPotProjectionDefinition.DEFINITION),
				reader.definitions);
	}

	@Test
	void authorizesAnActiveShareholderByUserIdNotByShareholderId() {
		UUID memberUserId = UUID.randomUUID();
		var memberReader = new RecordingReader(
				readyAuth(USER_UUID, Map.of(SHAREHOLDER_A_UUID, memberUserId)),
				new ProjectionReadResult.Ready(
						ProjectionFixtures.readPotProjection(List.of(ProjectionFixtures.pot()))));
		assertInstanceOf(ReadPotResult.Ready.class, PotReads.create(memberReader).read(
				new UserId(memberUserId), VIEW, POT_ID, VERSION));

		var shareholderIdReader = new RecordingReader(
				readyAuth(USER_UUID, Map.of(SHAREHOLDER_A_UUID, memberUserId)));
		assertInstanceOf(ReadPotResult.Forbidden.class, PotReads.create(shareholderIdReader).read(
				new UserId(SHAREHOLDER_A_UUID), VIEW, POT_ID, VERSION));
		assertEquals(List.of(ProjectionFixtures.authKey()), shareholderIdReader.keys);
	}

	@Test
	void missingCapabilityReadsNoProjection() {
		var reader = new RecordingReader();
		assertInstanceOf(ReadPotResult.Forbidden.class,
				PotReads.create(reader).read(USER_ID, new TokenCapabilities(java.util.Set.of()), POT_ID, VERSION));
		assertEquals(List.of(), reader.keys);
	}

	@Test
	void authNotReadyAndFailedShortCircuitReadPot() {
		var notReady = new RecordingReader(new ProjectionReadResult.NotReady(ProjectionFixtures.authKey()));
		assertEquals(new ReadPotResult.AuthNotReady(ProjectionFixtures.authKey()),
				PotReads.create(notReady).read(USER_ID, VIEW, POT_ID, VERSION));
		assertEquals(List.of(ProjectionFixtures.authKey()), notReady.keys);

		var failed = new RecordingReader(new ProjectionReadResult.Failed(ProjectionFixtures.authKey()));
		assertEquals(new ReadPotResult.AuthFailed(ProjectionFixtures.authKey()),
				PotReads.create(failed).read(USER_ID, VIEW, POT_ID, VERSION));
		assertEquals(List.of(ProjectionFixtures.authKey()), failed.keys);
	}

	@Test
	void anUnauthorizedUserShortCircuitsReadPot() {
		var reader = new RecordingReader(readyAuth(USER_UUID, Map.of()));
		assertInstanceOf(ReadPotResult.Forbidden.class,
				PotReads.create(reader).read(new UserId(UUID.randomUUID()), VIEW, POT_ID, VERSION));
		assertEquals(List.of(ProjectionFixtures.authKey()), reader.keys);
	}

	@Test
	void mapsReadPotFailedAndNotReadyAfterAuthorization() {
		var failed = new RecordingReader(readyAuth(USER_UUID, Map.of()),
				new ProjectionReadResult.Failed(ProjectionFixtures.readPotKey()));
		assertEquals(new ReadPotResult.ReadPotFailed(ProjectionFixtures.readPotKey()),
				PotReads.create(failed).read(USER_ID, VIEW, POT_ID, VERSION));

		var notReady = new RecordingReader(readyAuth(USER_UUID, Map.of()),
				new ProjectionReadResult.NotReady(ProjectionFixtures.readPotKey()));
		assertEquals(new ReadPotResult.ReadPotNotReady(ProjectionFixtures.readPotKey()),
				PotReads.create(notReady).read(USER_ID, VIEW, POT_ID, VERSION));
	}

	@Test
	void requiresAllInputsAndAPositiveVersionBeforeReading() {
		var reader = new RecordingReader();
		ReadPotUseCase useCase = PotReads.create(reader);
		assertThrows(NullPointerException.class, () -> useCase.read(null, VIEW, POT_ID, VERSION));
		assertThrows(NullPointerException.class, () -> useCase.read(USER_ID, null, POT_ID, VERSION));
		assertThrows(NullPointerException.class, () -> useCase.read(USER_ID, VIEW, null, VERSION));
		assertThrows(IllegalArgumentException.class, () -> useCase.read(USER_ID, VIEW, POT_ID, 0));
		assertEquals(List.of(), reader.keys);
	}

	private static ProjectionReadResult.Ready readyAuth(UUID creator, Map<UUID, UUID> members) {
		return new ProjectionReadResult.Ready(ProjectionFixtures.authProjection(creator, members));
	}

	private static final class RecordingReader implements ExactProjectionReadUseCase {
		private final Queue<ProjectionReadResult> results;
		private final List<ProjectionKey> keys = new ArrayList<>();
		private final List<ProjectionDefinition> definitions = new ArrayList<>();

		private RecordingReader(ProjectionReadResult... results) {
			this.results = new ArrayDeque<>(List.of(results));
		}

		@Override
		public ProjectionReadResult get(ProjectionKey key, ProjectionDefinition definition) {
			keys.add(key);
			definitions.add(definition);
			if (results.isEmpty()) throw new AssertionError("unexpected projection read: " + key);
			return results.remove();
		}
	}
}
