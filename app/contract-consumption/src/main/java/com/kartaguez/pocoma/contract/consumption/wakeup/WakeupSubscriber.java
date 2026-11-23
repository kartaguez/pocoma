package com.kartaguez.pocoma.contract.consumption.wakeup;

import java.util.Set;
import java.util.function.Consumer;

/** Best-effort volatile wakeup subscriber attached to a ConsumptionWorker. */
public interface WakeupSubscriber extends AutoCloseable {
    WakeupSubscriber NOOP =
            new WakeupSubscriber() {
                @Override
                public void start(
                        Set<WakeupKey> keys,
                        Consumer<WakeupKey> onWakeup,
                        Consumer<WakeupState> onStateChange) {
                    if (onStateChange != null) onStateChange.accept(WakeupState.NOOP);
                }

                @Override
                public WakeupState state() {
                    return WakeupState.NOOP;
                }
            };

    void start(
            Set<WakeupKey> keys,
            Consumer<WakeupKey> onWakeup,
            Consumer<WakeupState> onStateChange);

    WakeupState state();

    @Override
    default void close() {}
}
