package com.kartaguez.pocoma.contract.readquery;
import java.util.function.IntSupplier;
/** Observations have no authority over admission or transaction decisions. */
public interface ReadObservation {
    ReadObservation NONE = new ReadObservation() {};
    default void quota(IntSupplier available) {}
    default void rejected() {}
    default void record(String operation,String outcome,long elapsedNanos) {}
}
