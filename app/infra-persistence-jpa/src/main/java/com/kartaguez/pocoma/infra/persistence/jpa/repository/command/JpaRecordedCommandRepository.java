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
                auth_issuer, auth_subject, binding_id, auth_valid_until,
                auth_external_authorities_json
            ) values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
            on conflict (command_id) do nothing
            """;
    private static final String SELECT_BY_ID = """
            select command_id, command_type, payload_json, submitted_at,
                   auth_issuer, auth_subject, binding_id, auth_valid_until,
                   auth_external_authorities_json::text
            from recorded_commands where command_id = ?
            """;

    private final JdbcTemplate jdbc;

    public JpaRecordedCommandRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public int insert(RecordedCommandRow row) {
        return jdbc.update(INSERT, row.commandId(), row.commandType(), row.payloadJson(),
                Timestamp.from(row.submittedAt()), row.authIssuer(), row.authSubject(),
                row.bindingId(), Timestamp.from(row.authValidUntil()),
                row.authExternalAuthoritiesJson());
    }

    public Optional<RecordedCommandRow> findById(UUID commandId) {
        return jdbc.query(SELECT_BY_ID, JpaRecordedCommandRepository::map, commandId).stream().findFirst();
    }

    private static RecordedCommandRow map(ResultSet result, int rowNumber) throws SQLException {
        return new RecordedCommandRow(result.getObject("command_id", UUID.class),
                result.getString("command_type"), result.getString("payload_json"),
                result.getTimestamp("submitted_at").toInstant(), result.getString("auth_issuer"),
                result.getString("auth_subject"), result.getObject("binding_id", UUID.class),
                result.getTimestamp("auth_valid_until").toInstant(),
                result.getString("auth_external_authorities_json"));
    }
}
