package com.kartaguez.pocoma.contract.consumption.spi;
import com.kartaguez.pocoma.contract.consumption.participation.*;
import com.kartaguez.pocoma.contract.consumption.spi.*;
import com.kartaguez.pocoma.contract.consumption.spi.RecoveryObservation;

/** Observed business state; NOT_COMPLETED never proves physical rollback. */
public enum RecoveryObservation {
    COMPLETED,
    NOT_COMPLETED,
    INCONSISTENT,
    UNAVAILABLE
}
