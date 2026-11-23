package com.kartaguez.pocoma.contract.consumption.wakeup;

import com.kartaguez.pocoma.domain.consumption.ConsumableFamily;
import com.kartaguez.pocoma.domain.consumption.RoutingV1;
import java.util.UUID;

/**
 * Best-effort volatile wakeup publisher invoked strictly AFTER PostgreSQL COMMIT.
 * Implementations MUST NEVER throw to callers, MUST NEVER block business threads,
 * and MUST bound internal buffering.
 */
public interface WakeupPublisher extends AutoCloseable {
    WakeupPublisher NOOP = (family, partition) -> {};

    void publish(ConsumableFamily family, int partition);

    default void publishForId(ConsumableFamily family, UUID consumableId) {
        if (family == null || consumableId == null) return;
        int partition =
                RoutingV1.partition(
                        RoutingV1.hash(family, consumableId), WakeupKey.DEFAULT_PARTITION_COUNT);
        publish(family, partition);
    }

    @Override
    default void close() {}
}
