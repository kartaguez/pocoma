package com.kartaguez.pocoma.runtime.consumption.v3.maintenance;

import java.util.Map;

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
     * Atomically acquires maintenance exclusivity from READY state.
     * Transitions state -> MAINTENANCE, phase -> DRAINING.
     */
    boolean tryAcquireMaintenance(String operatorId);

    /**
     * Atomically updates the current phase of the active maintenance/rebuild operation.
     */
    boolean updatePhase(ReadMaintenanceState.Phase phase);

    /**
     * Atomically transitions to REBUILDING state with phase REPLAYING, recording the immutable target prefix B.
     */
    boolean startRebuilding(Map<String, Long> targetPrefix);

    /**
     * Atomically records phase CONVERGED in REBUILDING state.
     */
    boolean markConverged();

    /**
     * Atomically releases maintenance and opens READ back to service: state -> READY, phase -> IDLE.
     */
    boolean markReady();

    /**
     * Atomically marks the operation as FAILED with diagnostic error details.
     */
    boolean markFailed(String errorDetail);
}
