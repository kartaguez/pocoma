package com.kartaguez.pocoma.port.projection.v3.maintenance;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable representation of the durable READ maintenance and rebuild state.
 * Stored in public.read_maintenance_state in pocoma_read.
 */
public record ReadMaintenanceState(
        State state,
        Phase phase,
        Map<String, Long> targetPrefix,
        String operatorId,
        UUID operationId,
        Instant startedAt,
        Instant updatedAt,
        String errorDetail) {

    public enum State {
        READY,
        MAINTENANCE,
        REBUILDING,
        FAILED
    }

    public enum Phase {
        IDLE,
        DRAINING,
        EXCLUDED,
        SCHEMA_DROPPED,
        SCHEMA_CREATED,
        MIGRATED,
        ROUTED,
        REPLAYING,
        CONVERGED
    }

    public ReadMaintenanceState {
        Objects.requireNonNull(state, "state cannot be null");
        Objects.requireNonNull(phase, "phase cannot be null");
        targetPrefix = targetPrefix == null ? Map.of() : Collections.unmodifiableMap(Map.copyOf(targetPrefix));
    }

    public ReadMaintenanceState(
            State state,
            Phase phase,
            Map<String, Long> targetPrefix,
            String operatorId,
            Instant startedAt,
            Instant updatedAt,
            String errorDetail) {
        this(state, phase, targetPrefix, operatorId, null, startedAt, updatedAt, errorDetail);
    }

    public boolean isReady() {
        return state == State.READY;
    }

    public boolean isMaintenanceOrRebuilding() {
        return state == State.MAINTENANCE || state == State.REBUILDING;
    }

    public static ReadMaintenanceState initialReady() {
        return new ReadMaintenanceState(
                State.READY,
                Phase.IDLE,
                Map.of(),
                null,
                null,
                null,
                Instant.now(),
                null);
    }

    public static ReadMaintenanceState failed(String error) {
        return new ReadMaintenanceState(
                State.FAILED,
                Phase.IDLE,
                Map.of(),
                null,
                null,
                null,
                Instant.now(),
                error);
    }
}
