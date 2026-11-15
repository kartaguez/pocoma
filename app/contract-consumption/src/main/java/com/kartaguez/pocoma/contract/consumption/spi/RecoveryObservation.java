package com.kartaguez.pocoma.contract.consumption.spi;

/** Observed business state; NOT_COMPLETED never proves physical rollback. */
public enum RecoveryObservation {
    COMPLETED,
    NOT_COMPLETED,
    INCONSISTENT,
    UNAVAILABLE
}
