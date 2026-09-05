package com.kartaguez.pocoma.infra.persistence.jpa.repository.command;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JpaRecordedCommandRepository {
	private static final String INSERT = """
			insert into recorded_commands (
			    command_id, command_type, payload_json, submitted_at,
			    auth_user_id, auth_issuer, auth_authenticated_at, auth_issued_at,
			    auth_valid_until, auth_permissions_json
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
			on conflict (command_id) do nothing
			""";
	private static final String SELECT_BY_ID = """
			select command_id, command_type, payload_json, submitted_at,
			       auth_user_id, auth_issuer, auth_authenticated_at, auth_issued_at,
			       auth_valid_until, auth_permissions_json::text
			from recorded_commands
			where command_id = ?
			""";

	private final JdbcTemplate jdbc;

	public JpaRecordedCommandRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public int insert(RecordedCommandRow row) {
		return jdbc.update(INSERT, row.commandId(), row.commandType(), row.payloadJson(),
				Timestamp.from(row.submittedAt()), row.authUserId(), row.authIssuer(),
				Timestamp.from(row.authAuthenticatedAt()), Timestamp.from(row.authIssuedAt()),
				Timestamp.from(row.authValidUntil()), row.authPermissionsJson());
	}

	public Optional<RecordedCommandRow> findById(UUID commandId) {
		return jdbc.query(SELECT_BY_ID, JpaRecordedCommandRepository::map, commandId).stream().findFirst();
	}

	private static RecordedCommandRow map(ResultSet result, int rowNumber) throws SQLException {
		return new RecordedCommandRow(
				result.getObject("command_id", UUID.class), result.getString("command_type"),
				result.getString("payload_json"), result.getTimestamp("submitted_at").toInstant(),
				result.getObject("auth_user_id", UUID.class), result.getString("auth_issuer"),
				result.getTimestamp("auth_authenticated_at").toInstant(),
				result.getTimestamp("auth_issued_at").toInstant(),
				result.getTimestamp("auth_valid_until").toInstant(),
				result.getString("auth_permissions_json"));
	}
}
