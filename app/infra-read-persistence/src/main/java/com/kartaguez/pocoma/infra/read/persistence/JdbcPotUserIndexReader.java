package com.kartaguez.pocoma.infra.read.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcOperations;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.PotProjectionStatus;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifactId;
import com.kartaguez.pocoma.engine.read.projection.PotListCursor;
import com.kartaguez.pocoma.engine.read.projection.PotUserIndexEntry;
import com.kartaguez.pocoma.engine.read.projection.PotUserIndexPage;
import com.kartaguez.pocoma.engine.read.projection.PotUserIndexQuery;
import com.kartaguez.pocoma.engine.read.projection.PotUserIndexReader;

public final class JdbcPotUserIndexReader implements PotUserIndexReader {
	private static final String VALID_SCHEMA_NAME = "[A-Za-z_][A-Za-z0-9_]*";

	private final JdbcOperations jdbc;
	private final String schema;

	public JdbcPotUserIndexReader(JdbcOperations jdbc, String schema) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
		if (schema == null || !schema.matches(VALID_SCHEMA_NAME)) {
			throw new IllegalArgumentException("invalid schema");
		}
		this.schema = schema;
	}

	@Override
	public PotUserIndexPage findProjectedPots(PotUserIndexQuery query) {
		var sql = new StringBuilder("""
				select i.pipeline_version,i.pot_id,i.pot_version,i.updated_at,i.pot_status,i.artifact_id
				from %s.pot_projection_user_index i
				join %s.source_version_watermarks w on w.pot_id=i.pot_id
				where i.user_id=? and i.pipeline_id=? and i.pot_version=w.latest_version_seen
				and (
				""".formatted(schema, schema));
		var parameters = new ArrayList<Object>();
		parameters.add(query.userId().value());
		parameters.add(query.pipelineId().value());

		for (int index = 0; index < query.selectedRanges().size(); index++) {
			if (index > 0) {
				sql.append(" or ");
			}
			var range = query.selectedRanges().get(index);
			sql.append("(i.pipeline_version=? and w.latest_version_seen>=?");
			parameters.add(range.pipelineVersion());
			parameters.add(range.fromVersionInclusive());
			if (range.toVersionExclusive().isPresent()) {
				sql.append(" and w.latest_version_seen<?");
				parameters.add(range.toVersionExclusive().getAsLong());
			}
			sql.append(')');
		}
		sql.append(')');

		if (!query.includeDeleted()) {
			sql.append(" and i.pot_status='ACTIVE'");
		}
		query.after().ifPresent(cursor -> {
			sql.append(" and (i.updated_at<? or (i.updated_at=? and i.pot_id>?))");
			parameters.add(java.sql.Timestamp.from(cursor.updatedAt()));
			parameters.add(java.sql.Timestamp.from(cursor.updatedAt()));
			parameters.add(cursor.potId().value());
		});
		sql.append(" order by i.updated_at desc,i.pot_id asc limit ?");
		parameters.add(query.limit() + 1);

		List<PotUserIndexEntry> rows = jdbc.query(
				sql.toString(),
				(rs, row) -> new PotUserIndexEntry(
						new PipelineDefinition(PipelineId.of(query.pipelineId().value()), rs.getInt(1)),
						PotId.of(rs.getObject(2, UUID.class)),
						rs.getLong(3),
						rs.getTimestamp(4).toInstant(),
						PotProjectionStatus.valueOf(rs.getString(5)),
						new ProjectionArtifactId(rs.getObject(6, UUID.class))),
				parameters.toArray());

		boolean hasNext = rows.size() > query.limit();
		var entries = hasNext ? List.copyOf(rows.subList(0, query.limit())) : List.copyOf(rows);
		Optional<PotListCursor> next = hasNext
				? Optional.of(new PotListCursor(entries.getLast().updatedAt(), entries.getLast().potId()))
				: Optional.empty();
		return new PotUserIndexPage(entries, next);
	}
}
