package com.kartaguez.pocoma.runtime.registrationresult;

import org.springframework.context.SmartLifecycle;
import com.kartaguez.pocoma.orchestrator.poll.consumption.ConsumptionPollingWorker;

public final class RegistrationResultWorkerLifecycle implements SmartLifecycle {
    private final ConsumptionPollingWorker worker;
    private boolean started;
    public RegistrationResultWorkerLifecycle(ConsumptionPollingWorker worker) { this.worker = worker; }
    @Override public void start() { worker.start(); started = true; }
    @Override public void stop() { worker.requestStop(); started = false; }
    @Override public void stop(Runnable callback) { worker.requestStop(callback); started = false; }
    @Override public boolean isRunning() { return started && worker.isRunning(); }
}
