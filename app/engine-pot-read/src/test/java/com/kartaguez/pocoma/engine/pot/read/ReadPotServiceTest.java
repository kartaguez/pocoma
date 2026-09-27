package com.kartaguez.pocoma.engine.pot.read;

import static com.kartaguez.pocoma.engine.pot.read.ProjectionFixtures.POT_UUID;
import static com.kartaguez.pocoma.engine.pot.read.ProjectionFixtures.VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.projection.definition.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.ProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.engine.port.in.projection.read.ExactProjectionReadUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.read.ProjectionReadResult;

class ReadPotServiceTest {
	private static final PotId POT_ID = new PotId(POT_UUID);

	@Test
	void readsOnlyTheExactReadPotKeyAndInterpretsReady() {
		var reader = new RecordingReader(new ProjectionReadResult.Ready(
				ProjectionFixtures.readPotProjection(List.of(ProjectionFixtures.pot()))));

		var ready = assertInstanceOf(ReadPotResult.Ready.class,
				PotReads.create(reader).read(POT_ID, VERSION));

		assertEquals(POT_ID, ready.pot().potId());
		assertEquals(VERSION, ready.pot().version());
		assertEquals(List.of(ProjectionFixtures.readPotKey()), reader.keys);
		assertEquals(List.of(ReadPotProjectionDefinition.DEFINITION), reader.definitions);
	}

	@Test
	void mapsFailedAndNotReadyWithoutChangingTheExactKey() {
		ProjectionKey key = ProjectionFixtures.readPotKey();
		assertEquals(new ReadPotResult.Failed(key),
				PotReads.create((requested, definition) -> new ProjectionReadResult.Failed(requested))
						.read(POT_ID, VERSION));
		assertEquals(new ReadPotResult.NotReady(key),
				PotReads.create((requested, definition) -> new ProjectionReadResult.NotReady(requested))
						.read(POT_ID, VERSION));
	}

	@Test
	void requiresAnExplicitPositiveVersionBeforeReading() {
		var reader = new RecordingReader(new ProjectionReadResult.NotReady(ProjectionFixtures.readPotKey()));
		ReadPotUseCase useCase = PotReads.create(reader);

		assertThrows(NullPointerException.class, () -> useCase.read(null, VERSION));
		assertThrows(IllegalArgumentException.class, () -> useCase.read(POT_ID, 0));
		assertThrows(IllegalArgumentException.class, () -> useCase.read(POT_ID, -1));
		assertEquals(List.of(), reader.keys);
	}

	private static final class RecordingReader implements ExactProjectionReadUseCase {
		private final ProjectionReadResult result;
		private final List<ProjectionKey> keys = new ArrayList<>();
		private final List<ProjectionDefinition> definitions = new ArrayList<>();

		private RecordingReader(ProjectionReadResult result) {
			this.result = result;
		}

		@Override
		public ProjectionReadResult get(ProjectionKey key, ProjectionDefinition definition) {
			keys.add(key);
			definitions.add(definition);
			return result;
		}
	}
}
