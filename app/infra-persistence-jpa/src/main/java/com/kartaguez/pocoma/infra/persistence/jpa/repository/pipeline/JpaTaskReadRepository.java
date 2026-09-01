package com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Structural Task queries. No lifecycle or consumption table is consulted. */
@Repository
public class JpaTaskReadRepository {
	public static final String SELECT_BY_ID = """
			select id, pipeline_id, pipeline_version, partition_key, target_version,
			       created_at, task_type, task_payload
			from tasks_4_pipeline where id = ?
			""";

	private final JdbcTemplate jdbc;
	public JpaTaskReadRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	public Optional<TaskRow> findById(UUID taskId) {
		return jdbc.query(SELECT_BY_ID, JpaTaskReadRepository::map, taskId).stream().findFirst();
	}

	public static TaskRow map(ResultSet result, int rowNumber) throws SQLException {
		return new TaskRow(result.getObject("id", UUID.class), result.getString("pipeline_id"),
				result.getInt("pipeline_version"), result.getString("partition_key"),
				result.getLong("target_version"), result.getTimestamp("created_at").toInstant(),
				result.getString("task_type"), result.getString("task_payload"));
	}

	public record TaskRow(UUID taskId, String pipelineId, int pipelineVersion, String partitionKey,
			long targetVersion, Instant createdAt, String taskType, String taskPayload) {}
}
