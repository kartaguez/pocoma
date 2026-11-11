package com.kartaguez.pocoma.contract.writeadmission;

/** Safe public failure; never retain payload, key, token, SQL or provider diagnostics. */
public final class AdmissionFailure extends RuntimeException {
    private final int status;
    private final boolean physicalTerminationObserved;

    public AdmissionFailure(int status) {
        this(status, true);
    }

    public AdmissionFailure(int status, boolean physicalTerminationObserved) {
        super("Admission unavailable", null, false, false);
        this.status = status;
        this.physicalTerminationObserved = physicalTerminationObserved;
    }

    public boolean physicalTerminationObserved() {
        return physicalTerminationObserved;
    }

    public int status() {
        return status;
    }

    public static void require(boolean condition) {
        if (!condition) throw new AdmissionFailure(400);
    }
}
