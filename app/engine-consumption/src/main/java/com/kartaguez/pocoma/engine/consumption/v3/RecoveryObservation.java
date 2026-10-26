package com.kartaguez.pocoma.engine.consumption.v3;

/** Observed business state; NOT_COMPLETED never proves physical rollback. */
public enum RecoveryObservation {
    COMPLETED,
    NOT_COMPLETED,
    INCONSISTENT,
    UNAVAILABLE
}
