package com.kartaguez.pocoma.infra.persistence.read.jdbc.v3.maintenance;
import com.kartaguez.pocoma.port.projection.v3.maintenance.*;
import com.kartaguez.pocoma.port.projection.v3.maintenance.ReadMaintenanceAuthority;
import com.kartaguez.pocoma.port.projection.v3.maintenance.ReadMaintenanceState;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * PostgreSQL JDBC implementation of ReadMaintenanceAuthority.
 * Targets public.read_maintenance_state in pocoma_read.
 */
public final class JdbcReadMaintenanceStatus implements ReadMaintenanceStatus {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcReadMaintenanceStatus(DataSource dataSource) {
        this(new JdbcTemplate(dataSource), new ObjectMapper());
    }

    public JdbcReadMaintenanceStatus(JdbcTemplate jdbc) {
        this(jdbc, new ObjectMapper());
    }

    public JdbcReadMaintenanceStatus(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc cannot be null");
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    @Override
    public ReadMaintenanceState currentState() {
        String sql = "SELECT state, phase, target_prefix::text AS target_prefix_text, operator_id, operation_id, "
                + "started_at, updated_at, error_detail "
                + "FROM public.read_maintenance_state WHERE id = 1";

        List<ReadMaintenanceState> states = jdbc.query(sql, new StateRowMapper());
        if (states.isEmpty()) {
            throw new IllegalStateException("public.read_maintenance_state row id=1 is missing");
        }
        return states.getFirst();
    }

    private final class StateRowMapper implements RowMapper<ReadMaintenanceState> {
        @Override
        public ReadMaintenanceState mapRow(ResultSet rs, int rowNum) throws SQLException {
            String stateStr = rs.getString("state");
            String phaseStr = rs.getString("phase");
            String prefixJson = rs.getString("target_prefix_text");
            String operatorId = rs.getString("operator_id");
            UUID operationId = (UUID) rs.getObject("operation_id");
            Timestamp startedAtTs = rs.getTimestamp("started_at");
            Timestamp updatedAtTs = rs.getTimestamp("updated_at");
            String errorDetail = rs.getString("error_detail");

            ReadMaintenanceState.State state;
            try {
                state = ReadMaintenanceState.State.valueOf(stateStr);
            } catch (Exception ex) {
                state = ReadMaintenanceState.State.FAILED;
            }

            ReadMaintenanceState.Phase phase;
            try {
                phase = ReadMaintenanceState.Phase.valueOf(phaseStr);
            } catch (Exception ex) {
                phase = ReadMaintenanceState.Phase.IDLE;
            }

            Map<String, Long> prefix = Map.of();
            if (prefixJson != null && !prefixJson.isBlank()) {
                try {
                    prefix = mapper.readValue(prefixJson, new TypeReference<Map<String, Long>>() {});
                } catch (Exception ignored) {
                }
            }

            Instant startedAt = startedAtTs != null ? startedAtTs.toInstant() : null;
            Instant updatedAt = updatedAtTs != null ? updatedAtTs.toInstant() : Instant.now();

            return new ReadMaintenanceState(
                    state,
                    phase,
                    prefix,
                    operatorId,
                    operationId,
                    startedAt,
                    updatedAt,
                    errorDetail);
        }
    }
}
