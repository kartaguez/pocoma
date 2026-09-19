package com.kartaguez.pocoma.engine.pot.read;

import static com.kartaguez.pocoma.engine.pot.read.ProjectionFixtures.CREATOR_UUID;
import static com.kartaguez.pocoma.engine.pot.read.ProjectionFixtures.OTHER_USER_UUID;
import static com.kartaguez.pocoma.engine.pot.read.ProjectionFixtures.POT_UUID;
import static com.kartaguez.pocoma.engine.pot.read.ProjectionFixtures.SHAREHOLDER_A_UUID;
import static com.kartaguez.pocoma.engine.pot.read.ProjectionFixtures.USER_UUID;
import static com.kartaguez.pocoma.engine.pot.read.ProjectionFixtures.VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.authorization.PocomaPermissions;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.ProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionValidationException;
import com.kartaguez.pocoma.engine.exception.projection.read.StoredProjectionInvariantViolationException;
import com.kartaguez.pocoma.engine.port.in.projection.read.ExactProjectionReadUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.read.ProjectionReadResult;

class GetPotAtVersionServiceTest {
	private static final PotId POT_ID = new PotId(POT_UUID);
	private static final UserId CREATOR = new UserId(CREATOR_UUID);
	private static final UserId SHAREHOLDER_USER = new UserId(USER_UUID);
	private static final UserId OUTSIDER = new UserId(OTHER_USER_UUID);
	private static final Set<Permission> POT_VIEW = Set.of(PocomaPermissions.POT_VIEW);

	@Test
	void missingGlobalPermissionForbidsBeforeReadingEvenForAConfiguredCreator() {
		var reader = reader(readyAuth(CREATOR_UUID, List.of()), readyPot());

		assertInstanceOf(GetPotAtVersionResult.Forbidden.class,
				service(reader).get(CREATOR, Set.of(), POT_ID, VERSION));
		assertEquals(List.of(), reader.calls);
	}

	@Test
	void missingGlobalPermissionForbidsBeforeReadingEvenForAConfiguredShareholder() {
		var reader = reader(readyAuth(CREATOR_UUID,
				List.of(ProjectionFixtures.association(USER_UUID, SHAREHOLDER_A_UUID))), readyPot());

		assertInstanceOf(GetPotAtVersionResult.Forbidden.class,
				service(reader).get(SHAREHOLDER_USER, Set.of(), POT_ID, VERSION));
		assertEquals(List.of(), reader.calls);
	}

	@Test
	void missingGlobalPermissionForbidsBeforeReadingForAnOutsider() {
		var reader = reader(readyAuth(CREATOR_UUID, List.of()), readyPot());

		assertInstanceOf(GetPotAtVersionResult.Forbidden.class,
				service(reader).get(OUTSIDER, Set.of(), POT_ID, VERSION));
		assertEquals(List.of(), reader.calls);
	}

	@Test
	void globalPermissionAndCreatorMembershipContinueToReadPot() {
		var reader = reader(readyAuth(CREATOR_UUID, List.of()), readyPot());

		assertInstanceOf(GetPotAtVersionResult.Ready.class,
				service(reader).get(CREATOR, POT_VIEW, POT_ID, VERSION));

		assertCanonicalReadOrder(reader);
	}

	@Test
	void globalPermissionAndShareholderMembershipContinueToReadPot() {
		var reader = reader(readyAuth(CREATOR_UUID,
				List.of(ProjectionFixtures.association(USER_UUID, SHAREHOLDER_A_UUID))), readyPot());

		assertInstanceOf(GetPotAtVersionResult.Ready.class,
				service(reader).get(SHAREHOLDER_USER, POT_VIEW, POT_ID, VERSION));

		assertCanonicalReadOrder(reader);
	}

	@Test
	void globalPermissionAloneDoesNotGrantAccessToThePot() {
		var reader = reader(readyAuth(CREATOR_UUID, List.of()), readyPot());

		assertInstanceOf(GetPotAtVersionResult.Forbidden.class,
				service(reader).get(OUTSIDER, POT_VIEW, POT_ID, VERSION));
		assertEquals(List.of(ProjectionFixtures.authKey()), reader.calledKeys());
	}

	@Test
	void mapsAuthFailedAndNotReadyWithoutReadingReadPot() {
		var failedReader = reader(new ProjectionReadResult.Failed(ProjectionFixtures.authKey()), readyPot());
		assertInstanceOf(GetPotAtVersionResult.AuthFailed.class,
				service(failedReader).get(CREATOR, POT_VIEW, POT_ID, VERSION));
		assertEquals(List.of(ProjectionFixtures.authKey()), failedReader.calledKeys());

		var notReadyReader = reader(new ProjectionReadResult.NotReady(ProjectionFixtures.authKey()), readyPot());
		assertInstanceOf(GetPotAtVersionResult.AuthNotReady.class,
				service(notReadyReader).get(CREATOR, POT_VIEW, POT_ID, VERSION));
		assertEquals(List.of(ProjectionFixtures.authKey()), notReadyReader.calledKeys());
	}

	@Test
	void mapsReadPotFailedAndNotReadyAfterAuthorization() {
		var failedReader = reader(readyAuth(CREATOR_UUID, List.of()),
				new ProjectionReadResult.Failed(ProjectionFixtures.readPotKey()));
		assertInstanceOf(GetPotAtVersionResult.ReadPotFailed.class,
				service(failedReader).get(CREATOR, POT_VIEW, POT_ID, VERSION));

		var notReadyReader = reader(readyAuth(CREATOR_UUID, List.of()),
				new ProjectionReadResult.NotReady(ProjectionFixtures.readPotKey()));
		assertInstanceOf(GetPotAtVersionResult.ReadPotNotReady.class,
				service(notReadyReader).get(CREATOR, POT_VIEW, POT_ID, VERSION));
	}

	@Test
	void rejectsInvalidInputsBeforeAnyRead() {
		var reader = reader(readyAuth(CREATOR_UUID, List.of()), readyPot());
		var service = service(reader);

		assertThrows(NullPointerException.class, () -> service.get(null, POT_VIEW, POT_ID, VERSION));
		assertThrows(NullPointerException.class, () -> service.get(CREATOR, null, POT_ID, VERSION));
		var permissionsWithNull = new HashSet<Permission>();
		permissionsWithNull.add(null);
		assertThrows(NullPointerException.class,
				() -> service.get(CREATOR, permissionsWithNull, POT_ID, VERSION));
		assertThrows(NullPointerException.class, () -> service.get(CREATOR, POT_VIEW, null, VERSION));
		assertThrows(IllegalArgumentException.class, () -> service.get(CREATOR, POT_VIEW, POT_ID, 0));
		assertEquals(List.of(), reader.calls);
	}

	@Test
	void propagatesAuthAndReadPotInvariantViolations() {
		var invalidAuthReader = reader(readyAuth(CREATOR_UUID, List.of(
				ProjectionFixtures.association(USER_UUID, SHAREHOLDER_A_UUID),
				ProjectionFixtures.association(OTHER_USER_UUID, SHAREHOLDER_A_UUID))), readyPot());
		assertThrows(AuthProjectionInvariantViolationException.class,
				() -> service(invalidAuthReader).get(CREATOR, POT_VIEW, POT_ID, VERSION));
		assertEquals(List.of(ProjectionFixtures.authKey()), invalidAuthReader.calledKeys());

		var invalidReadPot = new ProjectionReadResult.Ready(ProjectionFixtures.readPotProjection(List.of(
				ProjectionFixtures.pot(),
				ProjectionFixtures.shareholder(SHAREHOLDER_A_UUID, "Alice",
						ProjectionFixtures.jsonNull(), 1, 1),
				ProjectionFixtures.expense(SHAREHOLDER_A_UUID, List.of()))));
		var invalidReadPotReader = reader(readyAuth(CREATOR_UUID, List.of()), invalidReadPot);
		assertThrows(ReadPotInvariantViolationException.class,
				() -> service(invalidReadPotReader).get(CREATOR, POT_VIEW, POT_ID, VERSION));
	}

	@Test
	void propagatesStoredProjectionInvariantViolation() {
		ExactProjectionReadUseCase reader = (key, definition) -> {
			throw new StoredProjectionInvariantViolationException(key,
					new ProjectionValidationException("invalid stored projection"));
		};

		assertThrows(StoredProjectionInvariantViolationException.class,
				() -> service(reader).get(CREATOR, POT_VIEW, POT_ID, VERSION));
	}

	private static GetPotAtVersionService service(ExactProjectionReadUseCase reader) {
		return new GetPotAtVersionService(reader, new AuthProjectionInterpreter(), new ReadPotInterpreter());
	}

	private static ProjectionReadResult.Ready readyAuth(UUID creator,
			List<com.kartaguez.pocoma.domain.projection.ProjectionArtifact> associations) {
		return new ProjectionReadResult.Ready(ProjectionFixtures.authProjection(creator, associations));
	}

	private static ProjectionReadResult.Ready readyPot() {
		return new ProjectionReadResult.Ready(
				ProjectionFixtures.readPotProjection(List.of(ProjectionFixtures.pot())));
	}

	private static RecordingReader reader(ProjectionReadResult auth, ProjectionReadResult readPot) {
		return new RecordingReader(auth, readPot);
	}

	private static void assertCanonicalReadOrder(RecordingReader reader) {
		assertEquals(List.of(ProjectionFixtures.authKey(), ProjectionFixtures.readPotKey()), reader.calledKeys());
		assertEquals(AuthProjectionDefinition.DEFINITION, reader.calls.get(0).definition());
		assertEquals(ReadPotProjectionDefinition.DEFINITION, reader.calls.get(1).definition());
	}

	private static final class RecordingReader implements ExactProjectionReadUseCase {
		private final ProjectionReadResult auth;
		private final ProjectionReadResult readPot;
		private final List<Call> calls = new ArrayList<>();

		private RecordingReader(ProjectionReadResult auth, ProjectionReadResult readPot) {
			this.auth = auth;
			this.readPot = readPot;
		}

		@Override
		public ProjectionReadResult get(ProjectionKey key, ProjectionDefinition definition) {
			calls.add(new Call(key, definition));
			if (key.projectionType().equals(AuthProjectionDefinition.PROJECTION_TYPE)) {
				return auth;
			}
			return readPot;
		}

		private List<ProjectionKey> calledKeys() {
			return calls.stream().map(Call::key).toList();
		}
	}

	private record Call(ProjectionKey key, ProjectionDefinition definition) {
	}
}
