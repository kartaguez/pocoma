package com.kartaguez.pocoma.runtime.consumption.v3.maintenance;

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
public final class JdbcReadMaintenanceAuthority implements ReadMaintenanceAuthority {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcReadMaintenanceAuthority(DataSource dataSource) {
        this(new JdbcTemplate(dataSource), new ObjectMapper());
    }

    public JdbcReadMaintenanceAuthority(JdbcTemplate jdbc) {
        this(jdbc, new ObjectMapper());
    }

    public JdbcReadMaintenanceAuthority(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc cannot be null");
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    @Override
    public ReadMaintenanceState currentState() {
        String sql = "SELECT state, phase, target_prefix::text AS target_prefix_text, operator_id, "
                + "started_at, updated_at, error_detail "
                + "FROM public.read_maintenance_state WHERE id = 1";

        List<ReadMaintenanceState> states = jdbc.query(sql, new StateRowMapper());
        if (states.isEmpty()) {
            throw new IllegalStateException("public.read_maintenance_state row id=1 is missing");
        }
        return states.getFirst();
    }

    @Override
    public boolean tryAcquireMaintenance(String operatorId) {
        String sql = "UPDATE public.read_maintenance_state "
                + "SET state = 'MAINTENANCE', phase = 'DRAINING', operator_id = ?, "
                + "started_at = clock_timestamp(), updated_at = clock_timestamp(), error_detail = NULL "
                + "WHERE id = 1 AND state = 'READY'";
        int rows = jdbc.update(sql, operatorId);
        return rows == 1;
    }

    @Override
    public boolean updatePhase(ReadMaintenanceState.Phase phase) {
        Objects.requireNonNull(phase, "phase cannot be null");
        String sql = "UPDATE public.read_maintenance_state "
                + "SET phase = ?, updated_at = clock_timestamp() "
                + "WHERE id = 1 AND state IN ('MAINTENANCE', 'REBUILDING')";
        int rows = jdbc.update(sql, phase.name());
        return rows == 1;
    }

    @Override
    public boolean startRebuilding(Map<String, Long> targetPrefix) {
        Objects.requireNonNull(targetPrefix, "targetPrefix cannot be null");
        String json;
        try {
            json = mapper.writeValueAsString(targetPrefix);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to serialize targetPrefix to JSON", ex);
        }

        String sql = "UPDATE public.read_maintenance_state "
                + "SET state = 'REBUILDING', phase = 'REPLAYING', target_prefix = ?::jsonb, "
                + "updated_at = clock_timestamp() "
                + "WHERE id = 1 AND state = 'MAINTENANCE'";
        int rows = jdbc.update(sql, json);
        return rows == 1;
    }

    @Override
    public boolean markConverged() {
        String sql = "UPDATE public.read_maintenance_state "
                + "SET phase = 'CONVERGED', updated_at = clock_timestamp() "
                + "WHERE id = 1 AND state = 'REBUILDING'";
        int rows = jdbc.update(sql);
        return rows == 1;
    }

    @Override
    public boolean markReady() {
        String sql = "UPDATE public.read_maintenance_state "
                + "SET state = 'READY', phase = 'IDLE', operator_id = NULL, "
                + "updated_at = clock_timestamp(), error_detail = NULL "
                + "WHERE id = 1 AND state IN ('REBUILDING', 'MAINTENANCE')";
        int rows = jdbc.update(sql);
        return rows == 1;
    }

    @Override
    public boolean markFailed(String errorDetail) {
        String sql = "UPDATE public.read_maintenance_state "
                + "SET state = 'FAILED', updated_at = clock_timestamp(), error_detail = ? "
                + "WHERE id = 1";
        int rows = jdbc.update(sql, errorDetail);
        return rows == 1;
    }

    public void ensureInitialized() {
        String ddl = """
            CREATE TABLE IF NOT EXISTS public.read_maintenance_state (
                id INT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
                state VARCHAR(32) NOT NULL CHECK (state IN ('READY', 'MAINTENANCE', 'REBUILDING', 'FAILED')),
                phase VARCHAR(32) NOT NULL CHECK (phase IN (
                    'IDLE', 'DRAINING', 'EXCLUDED', 'SCHEMA_DROPPED',
                    'SCHEMA_CREATED', 'MIGRATED', 'ROUTED', 'REPLAYING', 'CONVERGED'
                )),
                target_prefix JSONB NULL,
                operator_id VARCHAR(64) NULL,
                started_at TIMESTAMPTZ NULL,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
                error_detail TEXT NULL
            );
            INSERT INTO public.read_maintenance_state (id, state, phase, updated_at)
            VALUES (1, 'READY', 'IDLE', clock_timestamp())
            ON CONFLICT (id) DO NOTHING;
            """;
        jdbc.execute(ddl);
    }

    private final class StateRowMapper implements RowMapper<ReadMaintenanceState> {
        @Override
        public ReadMaintenanceState mapRow(ResultSet rs, int rowNum) throws SQLException {
            String stateStr = rs.getString("state");
            String phaseStr = rs.getString("phase");
            String prefixJson = rs.getString("target_prefix_text");
            String operatorId = rs.getString("operator_id");
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
                    startedAt,
                    updatedAt,
                    errorDetail);
        }
    }
}
