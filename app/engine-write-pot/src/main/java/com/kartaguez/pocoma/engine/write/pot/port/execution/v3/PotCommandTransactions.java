package com.kartaguez.pocoma.engine.write.pot.port.execution.v3;

import java.util.function.Supplier;

/** Concrete Pot execution boundaries, not an engine retry loop. No ambient/independent effects. */
public interface PotCommandTransactions {
    <T> T snapshot(Supplier<T> preparation);

    <T> T write(Supplier<T> finalization);

    enum Resolution {
        ROLLED_BACK,
        UNKNOWN
    }

    final class Failure extends RuntimeException {
        private final Resolution resolution;

        public Failure(Resolution resolution, Throwable cause) {
            super("Pot transaction " + resolution, cause);
            this.resolution = resolution;
        }

        public Resolution resolution() {
            return resolution;
        }
    }
}
