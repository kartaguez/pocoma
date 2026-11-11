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
    public ReadMaintenanceState currentState() {return new JdbcReadMaintenanceStatus(jdbc,mapper).currentState();}

    @Override
    public boolean tryAcquireMaintenance(String operatorId, UUID operationId) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(operationId, "operationId cannot be null");
        String sql = "UPDATE public.read_maintenance_state "
                + "SET state = 'MAINTENANCE', phase = 'DRAINING', operator_id = ?, operation_id = ?, "
                + "started_at = clock_timestamp(), updated_at = clock_timestamp(), error_detail = NULL "
                + "WHERE id = 1 AND state = 'READY' AND phase = 'IDLE'";
        int rows = jdbc.update(sql, operatorId, operationId);
        return rows == 1;
    }

    @Override
    public boolean updatePhase(ReadMaintenanceState.Phase phase, UUID operationId) {
        Objects.requireNonNull(phase, "phase cannot be null");
        String sql = "UPDATE public.read_maintenance_state "
                + "SET phase = ?, updated_at = clock_timestamp() "
                + "WHERE id = 1 AND state IN ('MAINTENANCE', 'REBUILDING')"
                + (operationId != null ? " AND operation_id = ?" : "");
        int rows = operationId != null
                ? jdbc.update(sql, phase.name(), operationId)
                : jdbc.update(sql, phase.name());
        return rows == 1;
    }

    @Override
    public boolean startRebuilding(Map<String, Long> targetPrefix, UUID operationId) {
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
                + "WHERE id = 1 AND state = 'MAINTENANCE'"
                + (operationId != null ? " AND operation_id = ?" : "");
        int rows = operationId != null
                ? jdbc.update(sql, json, operationId)
                : jdbc.update(sql, json);
        return rows == 1;
    }

    @Override
    public boolean markConverged(UUID operationId) {
        String sql = "UPDATE public.read_maintenance_state "
                + "SET phase = 'CONVERGED', updated_at = clock_timestamp() "
                + "WHERE id = 1 AND state = 'REBUILDING'"
                + (operationId != null ? " AND operation_id = ?" : "");
        int rows = operationId != null
                ? jdbc.update(sql, operationId)
                : jdbc.update(sql);
        return rows == 1;
    }

    @Override
    public boolean markReady(UUID operationId) {
        String sql = "UPDATE public.read_maintenance_state "
                + "SET state = 'READY', phase = 'IDLE', operator_id = NULL, operation_id = NULL, "
                + "updated_at = clock_timestamp(), error_detail = NULL "
                + "WHERE id = 1 AND state IN ('REBUILDING', 'MAINTENANCE')"
                + (operationId != null ? " AND operation_id = ?" : "");
        int rows = operationId != null
                ? jdbc.update(sql, operationId)
                : jdbc.update(sql);
        return rows == 1;
    }

    @Override
    public boolean markFailed(String errorDetail, UUID operationId) {
        String sql = "UPDATE public.read_maintenance_state "
                + "SET state = 'FAILED', updated_at = clock_timestamp(), error_detail = ? "
                + "WHERE id = 1"
                + (operationId != null ? " AND operation_id = ?" : "");
        int rows = operationId != null
                ? jdbc.update(sql, errorDetail, operationId)
                : jdbc.update(sql, errorDetail);
        return rows == 1;
    }

    @Override
    public boolean resetFailed(String operatorId) {
        String sql = "UPDATE public.read_maintenance_state "
                + "SET state = 'READY', phase = 'IDLE', operator_id = NULL, operation_id = NULL, "
                + "target_prefix = NULL, started_at = NULL, error_detail = NULL, "
                + "updated_at = clock_timestamp() "
                + "WHERE id = 1 AND state = 'FAILED'";
        int rows = jdbc.update(sql);
        return rows == 1;
    }

    @Override
    public boolean tryAcquireRecovery(String operatorId, UUID operationId) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(operationId, "operationId cannot be null");
        String sql = "UPDATE public.read_maintenance_state "
                + "SET state = 'MAINTENANCE', phase = 'DRAINING', operator_id = ?, operation_id = ?, "
                + "started_at = clock_timestamp(), updated_at = clock_timestamp(), error_detail = NULL "
                + "WHERE id = 1 AND state IN ('MAINTENANCE', 'REBUILDING', 'FAILED')";
        int rows = jdbc.update(sql, operatorId, operationId);
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
                operation_id UUID NULL,
                started_at TIMESTAMPTZ NULL,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
                error_detail TEXT NULL
            );
            ALTER TABLE public.read_maintenance_state ADD COLUMN IF NOT EXISTS operation_id UUID NULL;
            INSERT INTO public.read_maintenance_state (id, state, phase, updated_at)
            VALUES (1, 'READY', 'IDLE', clock_timestamp())
            ON CONFLICT (id) DO NOTHING;
            """;
        jdbc.execute(ddl);
    }

}
