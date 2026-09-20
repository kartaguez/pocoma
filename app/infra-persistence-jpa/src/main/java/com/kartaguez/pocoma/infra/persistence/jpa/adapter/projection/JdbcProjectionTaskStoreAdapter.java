package com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection;

import static java.util.Objects.requireNonNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.projection.TargetObjectType;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTask;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskCandidate;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskKeys;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskStorePort;

public class JdbcProjectionTaskStoreAdapter implements ProjectionTaskStorePort {
	private final JdbcTemplate jdbc;

	public JdbcProjectionTaskStoreAdapter(JdbcTemplate jdbc) {
		this.jdbc = requireNonNull(jdbc, "jdbc must not be null");
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public ProjectionTask ensure(ProjectionKey key, Instant createdAt) {
		requireNonNull(key, "key must not be null");
		requireNonNull(createdAt, "createdAt must not be null");
		jdbc.update("""
				insert into projection_tasks (
				 id, projection_type, target_object_type, target_object_id, target_version, partition_hash, created_at
				) values (?, ?, ?, ?, ?, ?, ?)
				on conflict (projection_type, target_object_type, target_object_id, target_version) do nothing
				""", UUID.randomUUID(), key.projectionType().value(), key.targetObjectType().value(),
				key.targetObjectId().value(), key.targetVersion(), ProjectionTaskKeys.partitionHash(key),
				Timestamp.from(createdAt));
		return new ProjectionTask(key);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ProjectionTaskCandidate> findCandidates(
			Set<ProjectionType> projectionTypes, int segmentIndex, int segmentCount,
			Optional<Instant> afterCreatedAt, Optional<UUID> afterRowId, int limit) {
		requireNonNull(projectionTypes, "projectionTypes must not be null");
		if (projectionTypes.isEmpty() || projectionTypes.stream().anyMatch(java.util.Objects::isNull)) {
			throw new IllegalArgumentException("projectionTypes must contain at least one non-null type");
		}
		requireNonNull(afterCreatedAt, "afterCreatedAt must not be null");
		requireNonNull(afterRowId, "afterRowId must not be null");
		if (segmentCount < 1 || segmentIndex < 0 || segmentIndex >= segmentCount || limit < 1) {
			throw new IllegalArgumentException("invalid segment or limit");
		}
		if (afterCreatedAt.isPresent() != afterRowId.isPresent()) {
			throw new IllegalArgumentException("cursor components must be both present or both absent");
		}
		var sortedTypes = projectionTypes.stream().sorted(Comparator.comparing(ProjectionType::value)).toList();
		String placeholders = sortedTypes.stream().map(ignored -> "?").collect(Collectors.joining(", "));
		String sql = """
				select id, projection_type, target_object_type, target_object_id, target_version, created_at
				from projection_tasks task
				where projection_type in (%s)
				  and mod(mod(partition_hash, ?) + ?, ?) = ?
				  and not exists (
				    select 1 from consumption_slots slot
				    where slot.consumable_type = 'PROJECTION_TASK'
				      and slot.consumable_components = jsonb_build_array(
				        task.projection_type, task.target_object_type, task.target_object_id, task.target_version::text)
				      and slot.consumer_type = 'PROJECTION_EXECUTOR'
				      and slot.consumer_components = jsonb_build_array(task.projection_type)
				      and slot.status = 'DONE'
				  )
				%s
				order by created_at, id
				limit ?
				""".formatted(placeholders, afterCreatedAt.isPresent() ? "and (created_at, id) > (?, ?)" : "");
		var arguments = new ArrayList<Object>();
		sortedTypes.forEach(type -> arguments.add(type.value()));
		arguments.add(segmentCount); arguments.add(segmentCount); arguments.add(segmentCount); arguments.add(segmentIndex);
		if (afterCreatedAt.isPresent()) {
			arguments.add(Timestamp.from(afterCreatedAt.orElseThrow())); arguments.add(afterRowId.orElseThrow());
		}
		arguments.add(limit);
		return jdbc.query(sql, this::candidate, arguments.toArray());
	}

	private ProjectionTaskCandidate candidate(ResultSet result, int row) throws SQLException {
		ProjectionKey key = new ProjectionKey(
				new ProjectionType(result.getString("projection_type")),
				new TargetObjectType(result.getString("target_object_type")),
				new TargetObjectId(result.getString("target_object_id")),
				result.getLong("target_version"));
		return new ProjectionTaskCandidate(result.getObject("id", UUID.class), new ProjectionTask(key),
				result.getTimestamp("created_at").toInstant());
	}
}
