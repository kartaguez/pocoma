package com.kartaguez.pocoma.contract.consumption.wakeup;

/** Operating state of the optional wakeup transport. */
public enum WakeupState {
    /** Wakeup bus disabled: polling-only mode. */
    NOOP(0),
    /** Wakeup bus connected: slow periodic reconciliation polling + event-driven wakeup. */
    CONNECTED(1),
    /** Wakeup bus disconnected or unhealthy: automatic fallback to frequent polling. */
    DEGRADED(2);

    private final int code;

    WakeupState(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
