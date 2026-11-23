package com.kartaguez.pocoma.contract.consumption.wakeup;

/** Transport-agnostic telemetry hooks for wakeup observability (low-cardinality only). */
public interface WakeupMetrics {
    WakeupMetrics NOOP = new WakeupMetrics() {};

    default void onPublished() {}

    default void onCoalesced() {}

    default void onPublishError() {}

    default void onReceived() {}

    default void onWakeupUseful() {}

    default void onWakeupSpurious() {}

    default void onWakeupIgnoredNoLease() {}

    default void onFallbackTransition() {}

    default void onReconnect() {}

    default void onStateChanged(WakeupState state) {}

    default void onDiscoveryDelay(long nanos) {}
}
