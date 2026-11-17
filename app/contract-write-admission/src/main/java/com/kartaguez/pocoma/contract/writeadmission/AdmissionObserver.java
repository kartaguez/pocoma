package com.kartaguez.pocoma.contract.writeadmission;

@FunctionalInterface
public interface AdmissionObserver {
    void onCommandAdmitted(java.util.UUID commandId);
    AdmissionObserver NOOP = commandId -> {};
}
