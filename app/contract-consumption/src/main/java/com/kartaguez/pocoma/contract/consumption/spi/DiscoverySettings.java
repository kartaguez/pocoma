package com.kartaguez.pocoma.contract.consumption.spi;

import java.time.Duration;

public record DiscoverySettings(
            int windowSize,
            int pageSize,
            int insertBatchSize,
            long softCap,
            Duration lockTimeout,
            Duration statementTimeout) {
        public DiscoverySettings {
            if (windowSize < 1 || pageSize < 1 || insertBatchSize < 1 || softCap < 1)
                throw new IllegalArgumentException("positive bounds required");
            LifecycleSettings.micros(lockTimeout, false);
            LifecycleSettings.micros(statementTimeout, false);
            if (lockTimeout.toMillis() < 1
                    || statementTimeout.toMillis() < 1
                    || lockTimeout.compareTo(statementTimeout) > 0)
                throw new IllegalArgumentException("invalid SQL timeout bounds");
        }
    }
