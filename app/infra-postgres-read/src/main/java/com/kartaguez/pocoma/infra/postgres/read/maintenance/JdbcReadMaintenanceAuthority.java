package com.kartaguez.pocoma.infra.postgres.read.maintenance;
import com.kartaguez.pocoma.port.projection.maintenance.*;
import com.kartaguez.pocoma.port.projection.maintenance.ReadMaintenanceAuthority;
import com.kartaguez.pocoma.port.projection.maintenance.ReadMaintenanceState;

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
        return acquireMaintenanceWhere(
                operatorId, operationId, "state = 'READY' AND phase = 'IDLE'");
    }

    @Override
    public boolean updatePhase(ReadMaintenanceState.Phase phase, UUID operationId) {
        Objects.requireNonNull(phase, "phase cannot be null");
        String baseSql =
                "UPDATE public.read_maintenance_state "
                        + "SET phase = ?, updated_at = clock_timestamp() "
                        + "WHERE id = 1 AND state IN ('MAINTENANCE', 'REBUILDING')";
        return updateWithOptionalOperation(baseSql, operationId, phase.name());
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
        String baseSql =
                "UPDATE public.read_maintenance_state "
                        + "SET state = 'REBUILDING', phase = 'REPLAYING',"
                        + " target_prefix = ?::jsonb, updated_at = clock_timestamp() "
                        + "WHERE id = 1 AND state = 'MAINTENANCE'";
        return updateWithOptionalOperation(baseSql, operationId, json);
    }

    @Override
    public boolean markConverged(UUID operationId) {
        String baseSql =
                "UPDATE public.read_maintenance_state "
                        + "SET phase = 'CONVERGED', updated_at = clock_timestamp() "
                        + "WHERE id = 1 AND state = 'REBUILDING'";
        return updateWithOptionalOperation(baseSql, operationId);
    }

    @Override
    public boolean markReady(UUID operationId) {
        String baseSql =
                "UPDATE public.read_maintenance_state "
                        + "SET state = 'READY', phase = 'IDLE', operator_id = NULL,"
                        + " operation_id = NULL, updated_at = clock_timestamp(), error_detail ="
                        + " NULL WHERE id = 1 AND state IN ('REBUILDING', 'MAINTENANCE')";
        return updateWithOptionalOperation(baseSql, operationId);
    }

    @Override
    public boolean markFailed(String errorDetail, UUID operationId) {
        String baseSql =
                "UPDATE public.read_maintenance_state "
                        + "SET state = 'FAILED', updated_at = clock_timestamp(), error_detail = ? "
                        + "WHERE id = 1";
        return updateWithOptionalOperation(baseSql, operationId, errorDetail);
    }

    @Override
    public boolean resetFailed(String operatorId) {
        String sql =
                "UPDATE public.read_maintenance_state "
                        + "SET state = 'READY', phase = 'IDLE', operator_id = NULL,"
                        + " operation_id = NULL, target_prefix = NULL, started_at = NULL,"
                        + " error_detail = NULL, updated_at = clock_timestamp() "
                        + "WHERE id = 1 AND state = 'FAILED'";
        return jdbc.update(sql) == 1;
    }

    @Override
    public boolean tryAcquireRecovery(String operatorId, UUID operationId) {
        return acquireMaintenanceWhere(
                operatorId, operationId, "state IN ('MAINTENANCE', 'REBUILDING', 'FAILED')");
    }

    private boolean acquireMaintenanceWhere(
            String operatorId, UUID operationId, String statePredicate) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(operationId, "operationId cannot be null");
        String sql =
                "UPDATE public.read_maintenance_state "
                        + "SET state = 'MAINTENANCE', phase = 'DRAINING', operator_id = ?,"
                        + " operation_id = ?, started_at = clock_timestamp(), updated_at ="
                        + " clock_timestamp(), error_detail = NULL "
                        + "WHERE id = 1 AND "
                        + statePredicate;
        return jdbc.update(sql, operatorId, operationId) == 1;
    }

    private boolean updateWithOptionalOperation(
            String baseSql, UUID operationId, Object... baseArgs) {
        if (operationId == null) {
            return jdbc.update(baseSql, baseArgs) == 1;
        }
        Object[] args = Arrays.copyOf(baseArgs, baseArgs.length + 1);
        args[baseArgs.length] = operationId;
        return jdbc.update(baseSql + " AND operation_id = ?", args) == 1;
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
