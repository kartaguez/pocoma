package com.kartaguez.pocoma.infra.pipeline.lifecycle.persistence;

import static java.util.Objects.requireNonNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.pipeline.lifecycle.model.ServingSelection;
import com.kartaguez.pocoma.engine.pipeline.lifecycle.model.PipelineLifecycleStateSnapshot;
import com.kartaguez.pocoma.engine.port.in.pipeline.lifecycle.PipelineActivationQuery;
import com.kartaguez.pocoma.engine.port.in.pipeline.lifecycle.PipelineClaimActivationGate;
import com.kartaguez.pocoma.engine.port.in.pipeline.lifecycle.ServingSelectionQuery;
import com.kartaguez.pocoma.engine.port.out.pipeline.lifecycle.PipelineLifecycleStateMutationPort;
import com.kartaguez.pocoma.engine.port.out.pipeline.lifecycle.PipelineLifecycleStateSnapshotPort;

public class JdbcPipelineLifecycleAdapter implements PipelineLifecycleStateMutationPort,
		PipelineLifecycleStateSnapshotPort, PipelineActivationQuery, PipelineClaimActivationGate,
		ServingSelectionQuery {
	private static final String ACTIVATIONS = "pocoma_control.pipeline_version_activations";
	private static final String SELECTIONS = "pocoma_control.projection_serving_selections";

	private final JdbcTemplate jdbc;

	public JdbcPipelineLifecycleAdapter(JdbcTemplate jdbc) {
		this.jdbc = requireNonNull(jdbc, "jdbc must not be null");
	}

	@Override
	@Transactional
	public void activate(PipelineDefinition pipeline, Instant activatedAt) {
		requireNonNull(pipeline, "pipeline must not be null");
		requireNonNull(activatedAt, "activatedAt must not be null");
		jdbc.update("insert into " + ACTIVATIONS
				+ " (pipeline_id, pipeline_version, activated_at) values (?, ?, ?) on conflict do nothing",
				pipeline.pipelineId().value(), pipeline.pipelineVersion(), Timestamp.from(activatedAt));
	}

	@Override
	@Transactional
	public DeactivationResult deactivateIfNotServing(PipelineDefinition pipeline) {
		requireNonNull(pipeline, "pipeline must not be null");
		var activation = jdbc.query("select 1 from " + ACTIVATIONS
				+ " where pipeline_id = ? and pipeline_version = ? for update",
				(rs, row) -> rs.getInt(1), pipeline.pipelineId().value(), pipeline.pipelineVersion());
		if (activation.isEmpty()) return DeactivationResult.ALREADY_INACTIVE;
		Integer serving = jdbc.queryForObject("select count(*) from " + SELECTIONS
				+ " where pipeline_id = ? and pipeline_version = ?", Integer.class,
				pipeline.pipelineId().value(), pipeline.pipelineVersion());
		if (requireNonNull(serving, "count must not be null") != 0) return DeactivationResult.SERVING;
		int deleted = jdbc.update("delete from " + ACTIVATIONS
				+ " where pipeline_id = ? and pipeline_version = ?",
				pipeline.pipelineId().value(), pipeline.pipelineVersion());
		if (deleted != 1) throw new IllegalStateException("Expected one activation deletion, got " + deleted);
		return DeactivationResult.DEACTIVATED;
	}

	@Override
	@Transactional
	public ServingMutationResult selectServingIfActive(ServingSelection selection, Instant selectedAt) {
		requireNonNull(selection, "selection must not be null");
		requireNonNull(selectedAt, "selectedAt must not be null");
		PipelineDefinition pipeline = selection.servingPipeline();
		if (!lockIfActive(pipeline)) return ServingMutationResult.INACTIVE;
		Optional<ServingSelection> current = findServing(selection.projectionType());
		if (current.filter(selection::equals).isPresent()) return ServingMutationResult.UNCHANGED;
		jdbc.update("insert into " + SELECTIONS
				+ " (projection_type, pipeline_id, pipeline_version, selected_at) values (?, ?, ?, ?)"
				+ " on conflict (projection_type) do update set pipeline_id = excluded.pipeline_id,"
				+ " pipeline_version = excluded.pipeline_version, selected_at = excluded.selected_at",
				selection.projectionType().value(), pipeline.pipelineId().value(), pipeline.pipelineVersion(),
				Timestamp.from(selectedAt));
		return ServingMutationResult.SELECTED;
	}

	@Override
	@Transactional
	public void clearServing(ProjectionType projectionType) {
		requireNonNull(projectionType, "projectionType must not be null");
		jdbc.update("delete from " + SELECTIONS + " where projection_type = ?", projectionType.value());
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isActive(PipelineDefinition pipeline) {
		requireNonNull(pipeline, "pipeline must not be null");
		Integer count = jdbc.queryForObject("select count(*) from " + ACTIVATIONS
				+ " where pipeline_id = ? and pipeline_version = ?", Integer.class,
				pipeline.pipelineId().value(), pipeline.pipelineVersion());
		return requireNonNull(count, "count must not be null") == 1;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean lockIfActive(PipelineDefinition pipeline) {
		requireNonNull(pipeline, "pipeline must not be null");
		return !jdbc.query("select 1 from " + ACTIVATIONS
				+ " where pipeline_id = ? and pipeline_version = ? for key share",
				(rs, row) -> rs.getInt(1), pipeline.pipelineId().value(), pipeline.pipelineVersion()).isEmpty();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ServingSelection> findServing(ProjectionType projectionType) {
		requireNonNull(projectionType, "projectionType must not be null");
		return jdbc.query("select projection_type, pipeline_id, pipeline_version from " + SELECTIONS
				+ " where projection_type = ?", this::selection, projectionType.value()).stream().findFirst();
	}

	public Optional<Instant> activatedAt(PipelineDefinition pipeline) {
		requireNonNull(pipeline, "pipeline must not be null");
		return jdbc.query("select activated_at from " + ACTIVATIONS
				+ " where pipeline_id = ? and pipeline_version = ?",
				(rs, row) -> rs.getTimestamp(1).toInstant(), pipeline.pipelineId().value(), pipeline.pipelineVersion())
				.stream().findFirst();
	}

	public Optional<Instant> selectedAt(ProjectionType projectionType) {
		requireNonNull(projectionType, "projectionType must not be null");
		return jdbc.query("select selected_at from " + SELECTIONS + " where projection_type = ?",
				(rs, row) -> rs.getTimestamp(1).toInstant(), projectionType.value()).stream().findFirst();
	}

	@Override
	@Transactional(readOnly = true)
	public PipelineLifecycleStateSnapshot loadSnapshot() {
		var activations = jdbc.query("select pipeline_id, pipeline_version from " + ACTIVATIONS,
				(rs, row) -> new PipelineDefinition(
						PipelineId.of(rs.getString("pipeline_id")), rs.getInt("pipeline_version")));
		var selections = jdbc.query("select projection_type, pipeline_id, pipeline_version from " + SELECTIONS,
				this::selection);
		return new PipelineLifecycleStateSnapshot(activations, selections);
	}

	private ServingSelection selection(ResultSet rs, int row) throws SQLException {
		return new ServingSelection(new ProjectionType(rs.getString("projection_type")),
				new PipelineDefinition(PipelineId.of(rs.getString("pipeline_id")), rs.getInt("pipeline_version")));
	}
}
