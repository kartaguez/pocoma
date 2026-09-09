package com.kartaguez.pocoma.infra.persistence.jpa.adapter.pipeline;

import static java.util.Objects.requireNonNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.engine.port.in.taskcreation.result.PersistedTaskReference;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationResult;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationResult.Materialized;
import com.kartaguez.pocoma.engine.port.out.taskcreation.TaskCreationPort;
import com.kartaguez.pocoma.engine.port.out.taskcreation.input.EventPipelineTaskCreation;
import com.kartaguez.pocoma.engine.task.creation.TaskDescriptor;

/** Direct, immutable ensure/adopt for one Event-derived projection Task. */
@Component
public class JpaTaskCreationAdapter implements TaskCreationPort {
	private static final String FIND = """
			select id, event_id, pipeline_id, pipeline_version, pot_id, target_version,
			       task_type, task_key, task_payload, partition_key, created_at
			from tasks_4_pipeline
			where event_id = ? and pipeline_id = ? and pipeline_version = ?
			""";

	private final JdbcTemplate jdbc;
	private final Clock clock;

	@Autowired
	public JpaTaskCreationAdapter(JdbcTemplate jdbc) {
		this(jdbc, Clock.systemUTC());
	}

	JpaTaskCreationAdapter(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = requireNonNull(jdbc, "jdbc must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Materialized createIfAbsent(EventPipelineTaskCreation creation, List<TaskDescriptor> descriptors) {
		requireNonNull(creation, "creation must not be null");
		List<TaskDescriptor> immutable = List.copyOf(requireNonNull(descriptors, "tasks must not be null"));
		if (immutable.size() != 1) {
			throw new IllegalArgumentException("An Event-derived pipeline generation requires exactly one Task");
		}

		TaskDescriptor descriptor = immutable.getFirst();
		var pipeline = creation.pipeline();
		var event = creation.recordedEvent();
		UUID potId = event.event().potId().value();
		long potVersion = event.event().version();
		if (!potId.toString().equals(descriptor.partitionKey()) || descriptor.targetVersion() != potVersion) {
			throw new IllegalStateException("Task descriptor does not match its Event projection identity");
		}

		UUID taskId = UUID.randomUUID();
		Instant now = clock.instant();
		int inserted = jdbc.update("""
				insert into tasks_4_pipeline (
				  id, event_id, pipeline_id, pipeline_version, pot_id,
				  task_type, task_key, task_payload, partition_key, partition_hash, target_version,
				  status, attempt_count, created_at, updated_at)
				values (?,?,?,?,?,?,?,?,?,?,?,'PENDING',0,?,?)
				on conflict (event_id, pipeline_id, pipeline_version) do nothing
				""", taskId, event.eventId(), pipeline.pipelineId().value(), pipeline.pipelineVersion(), potId,
				descriptor.taskType(), descriptor.taskKey(), descriptor.taskPayload(), descriptor.partitionKey(),
				descriptor.partitionKey().hashCode() & Integer.MAX_VALUE, descriptor.targetVersion(),
				Timestamp.from(now), Timestamp.from(now));

		TaskRow winner = jdbc.query(FIND, JpaTaskCreationAdapter::map, event.eventId(),
				pipeline.pipelineId().value(), pipeline.pipelineVersion()).stream().findFirst()
				.orElseThrow(() -> new IllegalStateException("Task winner is not visible"));
		validate(winner, creation, descriptor);
		List<PersistedTaskReference> references = List.of(
				new PersistedTaskReference(winner.id(), winner.taskType(), winner.createdAt()));
		return inserted == 1 ? TaskCreationResult.created(creation, references)
				: TaskCreationResult.alreadyCreated(creation, references);
	}

	private static void validate(TaskRow winner, EventPipelineTaskCreation creation, TaskDescriptor expected) {
		var event = creation.recordedEvent();
		var pipeline = creation.pipeline();
		boolean coherent = winner.eventId().equals(event.eventId())
				&& winner.pipelineId().equals(pipeline.pipelineId().value())
				&& winner.pipelineVersion() == pipeline.pipelineVersion()
				&& winner.potId().equals(event.event().potId().value())
				&& winner.targetVersion() == event.event().version()
				&& winner.taskType().equals(expected.taskType())
				&& winner.taskKey().equals(expected.taskKey())
				&& winner.taskPayload().equals(expected.taskPayload())
				&& java.util.Objects.equals(winner.partitionKey(), expected.partitionKey());
		if (!coherent) {
			throw new IllegalStateException("Existing Task conflicts with immutable Event-derived identity");
		}
	}

	private static TaskRow map(ResultSet result, int row) throws SQLException {
		return new TaskRow(result.getObject("id", UUID.class), result.getObject("event_id", UUID.class),
				result.getString("pipeline_id"), result.getInt("pipeline_version"),
				result.getObject("pot_id", UUID.class), result.getLong("target_version"),
				result.getString("task_type"), result.getString("task_key"),
				result.getString("task_payload"), result.getString("partition_key"),
				result.getTimestamp("created_at").toInstant());
	}

	private record TaskRow(UUID id, UUID eventId, String pipelineId, int pipelineVersion, UUID potId,
			long targetVersion, String taskType, String taskKey, String taskPayload, String partitionKey,
			Instant createdAt) {}
}
