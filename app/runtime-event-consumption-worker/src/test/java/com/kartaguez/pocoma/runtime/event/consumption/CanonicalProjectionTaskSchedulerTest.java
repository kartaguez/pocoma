package com.kartaguez.pocoma.runtime.event.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.event.EventTraceMetadata;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.EventTaskSchedulingResult;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTask;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskCandidate;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskStorePort;

class CanonicalProjectionTaskSchedulerTest {
	private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");

	@Test
	void twoEventsRequestingTheSameVersionConvergeOnOneTaskPerProjectionKey() {
		var tasks = new InMemoryTasks();
		var scheduler = new CanonicalProjectionTaskScheduler(
				event -> new EventTaskSchedulingResult.Scheduled(event.eventId(), List.of()), tasks,
				Clock.fixed(NOW, ZoneOffset.UTC));
		PotId potId = PotId.of(UUID.fromString("10000000-0000-0000-0000-000000000001"));
		var firstEvent = event(UUID.fromString("30000000-0000-0000-0000-000000000001"), potId, 42);
		var secondEvent = event(UUID.fromString("30000000-0000-0000-0000-000000000002"), potId, 42);

		scheduler.schedule(firstEvent);
		scheduler.schedule(secondEvent);

		assertEquals(2, tasks.byKey.size());
		assertEquals(1, tasks.byKey.keySet().stream()
				.filter(key -> key.projectionType().value().equals("READ_POT")).count());
		assertEquals(1, tasks.byKey.keySet().stream()
				.filter(key -> key.projectionType().value().equals("POT_BALANCES")).count());
	}

	private static RecordedEvent<PotCreatedEvent> event(UUID eventId, PotId potId, long version) {
		return new RecordedEvent<>(eventId, new PotCreatedEvent(potId, version), NOW, EventTraceMetadata.empty());
	}

	private static final class InMemoryTasks implements ProjectionTaskStorePort {
		private final LinkedHashMap<ProjectionKey, ProjectionTask> byKey = new LinkedHashMap<>();

		@Override
		public ProjectionTask ensure(ProjectionKey key, Instant createdAt) {
			return byKey.computeIfAbsent(key, ProjectionTask::new);
		}

		@Override
		public List<ProjectionTaskCandidate> findCandidates(java.util.Set<ProjectionType> projectionTypes,
				int segmentIndex,
				int segmentCount, Optional<Instant> afterCreatedAt, Optional<UUID> afterRowId, int limit) {
			throw new UnsupportedOperationException();
		}
	}
}
