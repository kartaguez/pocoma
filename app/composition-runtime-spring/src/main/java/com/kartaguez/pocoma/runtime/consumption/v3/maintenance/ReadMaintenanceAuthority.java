package com.kartaguez.pocoma.runtime.consumption.v3.maintenance;

import java.util.Map;
import java.util.UUID;

/**
 * Interface for reading and atomically transitioning the durable READ maintenance state.
 */
public interface ReadMaintenanceAuthority {

    /**
     * Reads the current persistent state.
     * Throws an exception or returns a non-READY state if the authority is inaccessible.
     */
    ReadMaintenanceState currentState();

    /**
     * Fail-closed check: returns true if and only if the current persistent state is READY.
     * Any error, timeout, or inaccessible database returns false.
     */
    default boolean isReady() {
        try {
            return currentState().isReady();
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Atomically acquires maintenance exclusivity from READY state with phase IDLE.
     * Transitions state -> MAINTENANCE, phase -> DRAINING.
     */
    default boolean tryAcquireMaintenance(String operatorId) {
        return tryAcquireMaintenance(operatorId, UUID.randomUUID());
    }

    /**
     * Atomically acquires maintenance exclusivity with a designated operation UUID from READY state.
     */
    boolean tryAcquireMaintenance(String operatorId, UUID operationId);

    /**
     * Atomically updates the current phase of the active maintenance/rebuild operation.
     */
    default boolean updatePhase(ReadMaintenanceState.Phase phase) {
        return updatePhase(phase, null);
    }

    /**
     * Atomically updates the current phase conditioned on matching the active operation UUID.
     */
    boolean updatePhase(ReadMaintenanceState.Phase phase, UUID operationId);

    /**
     * Atomically transitions to REBUILDING state with phase REPLAYING, recording the immutable target prefix B.
     */
    default boolean startRebuilding(Map<String, Long> targetPrefix) {
        return startRebuilding(targetPrefix, null);
    }

    /**
     * Atomically transitions to REBUILDING state conditioned on matching the active operation UUID.
     */
    boolean startRebuilding(Map<String, Long> targetPrefix, UUID operationId);

    /**
     * Atomically records phase CONVERGED in REBUILDING state.
     */
    default boolean markConverged() {
        return markConverged(null);
    }

    /**
     * Atomically records phase CONVERGED conditioned on matching the active operation UUID.
     */
    boolean markConverged(UUID operationId);

    /**
     * Atomically releases maintenance and opens READ back to service: state -> READY, phase -> IDLE.
     */
    default boolean markReady() {
        return markReady(null);
    }

    /**
     * Atomically releases maintenance conditioned on matching the active operation UUID.
     */
    boolean markReady(UUID operationId);

    /**
     * Atomically marks the operation as FAILED with diagnostic error details.
     */
    default boolean markFailed(String errorDetail) {
        return markFailed(errorDetail, null);
    }

    /**
     * Atomically marks the operation as FAILED conditioned on matching the active operation UUID.
     */
    boolean markFailed(String errorDetail, UUID operationId);

    /**
     * Explicitly resets a FAILED maintenance state back to READY / IDLE.
     * Required before any new rebuild can be acquired after failure.
     */
    boolean resetFailed(String operatorId);
}
