package com.kartaguez.pocoma.infra.persistence.jpa.adapter.processing.event;

import static java.util.Objects.requireNonNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.event.EventType;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.projection.TargetObjectType;
import com.kartaguez.pocoma.engine.port.out.processing.event.ProjectionMaterializationCandidate;
import com.kartaguez.pocoma.engine.port.out.processing.event.ProjectionMaterializationDiscoveryPort;
import com.kartaguez.pocoma.engine.processing.event.ordering.ProjectionMaterializationOrderingKey;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;

/** PostgreSQL metadata-only discovery of due Event to Projection consequences. */
public class JdbcProjectionMaterializationDiscoveryAdapter implements ProjectionMaterializationDiscoveryPort {
	private static final TargetObjectType POT = new TargetObjectType("POT");

	private final JdbcTemplate jdbc;

	public JdbcProjectionMaterializationDiscoveryAdapter(JdbcTemplate jdbc) {
		this.jdbc = requireNonNull(jdbc, "jdbc must not be null");
	}

	@Override
	@Transactional(readOnly = true)
	public List<ProjectionMaterializationCandidate> findCandidates(
			Map<EventType, Set<ProjectionType>> suppliedRoutes,
			WorkerSegment segment,
			Optional<ProjectionMaterializationOrderingKey> afterExclusive,
			int limit) {
		requireNonNull(segment, "segment must not be null");
		requireNonNull(afterExclusive, "afterExclusive must not be null");
		if (limit < 1) throw new IllegalArgumentException("limit must be positive");

		List<SqlRoute> routes = routes(suppliedRoutes);
		if (routes.isEmpty()) return List.of();

		String values = String.join(",", Collections.nCopies(routes.size(),
				"(cast(? as varchar),cast(? as varchar))"));
		String cursor = afterExclusive.isPresent() ? """
				and (event.created_at, event.id, route.projection_type) > (?, ?, ?)
				""" : "";
		String sql = """
				with routes(event_type, projection_type) as (
				  values %s
				)
				select event.id, event.event_type, event.pot_id, event.version, event.created_at,
				       route.projection_type
				from business_event_outbox event
				join routes route on route.event_type = event.event_type
				where mod(mod(event.pot_partition_hash, ?) + ?, ?) = ?
				  and not exists (
				    select 1
				    from consumption_slots slot
				    where slot.consumable_type = 'EVENT'
				      and slot.consumable_components = jsonb_build_array(event.id::text)
				      and slot.consumer_type = 'PROJECTION_TASK_MATERIALIZER'
				      and slot.consumer_components = jsonb_build_array(route.projection_type)
				      and slot.status = 'DONE'
				  )
				%s
				order by event.created_at, event.id, route.projection_type
				limit ?
				""".formatted(values, cursor);

		var parameters = new ArrayList<Object>();
		for (SqlRoute route : routes) {
			parameters.add(route.eventType());
			parameters.add(route.projectionType());
		}
		parameters.add(segment.segmentCount());
		parameters.add(segment.segmentCount());
		parameters.add(segment.segmentCount());
		parameters.add(segment.segmentIndex());
		afterExclusive.ifPresent(key -> {
			parameters.add(Timestamp.from(key.recordedAt()));
			parameters.add(key.eventId());
			parameters.add(key.projectionType().value());
		});
		parameters.add(limit);

		return List.copyOf(jdbc.query(sql, this::candidate, parameters.toArray()));
	}

	private ProjectionMaterializationCandidate candidate(ResultSet result, int row) throws SQLException {
		UUID potId = result.getObject("pot_id", UUID.class);
		return new ProjectionMaterializationCandidate(
				result.getObject("id", UUID.class),
				new EventType(result.getString("event_type")),
				new ProjectionType(result.getString("projection_type")),
				POT,
				new TargetObjectId(potId.toString()),
				result.getLong("version"),
				result.getTimestamp("created_at").toInstant());
	}

	private static List<SqlRoute> routes(Map<EventType, Set<ProjectionType>> suppliedRoutes) {
		requireNonNull(suppliedRoutes, "routes must not be null");
		var routes = new ArrayList<SqlRoute>();
		for (var entry : suppliedRoutes.entrySet()) {
			EventType eventType = requireNonNull(entry.getKey(), "route eventType must not be null");
			Set<ProjectionType> projectionTypes = requireNonNull(entry.getValue(),
					"route projectionTypes must not be null");
			for (ProjectionType projectionType : projectionTypes) {
				requireNonNull(projectionType, "route projectionType must not be null");
				routes.add(new SqlRoute(eventType.value(), projectionType.value()));
			}
		}
		routes.sort(Comparator.comparing(SqlRoute::eventType).thenComparing(SqlRoute::projectionType));
		return List.copyOf(routes);
	}

	private record SqlRoute(String eventType, String projectionType) {}
}
