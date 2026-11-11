package com.kartaguez.pocoma.port.projection.v3.maintenance;
/** Consultable status, without an operation capable of changing maintenance authority. */
public interface ReadMaintenanceStatus {
    ReadMaintenanceState currentState();
    default boolean isReady() {try{return currentState().isReady();}catch(Exception unavailable){return false;}}
}
