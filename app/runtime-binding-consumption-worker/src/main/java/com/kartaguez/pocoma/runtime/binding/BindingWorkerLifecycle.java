package com.kartaguez.pocoma.runtime.binding;

import org.springframework.context.SmartLifecycle;
import com.kartaguez.pocoma.supra.consumption.ConsumptionPollingWorker;

final class BindingWorkerLifecycle implements SmartLifecycle {
	private final ConsumptionPollingWorker worker; private final Runnable bootstrap; private boolean started;
	BindingWorkerLifecycle(ConsumptionPollingWorker worker, Runnable bootstrap){this.worker=worker;this.bootstrap=bootstrap;}
	@Override public void start(){bootstrap.run();worker.start();started=true;}
	@Override public void stop(){worker.requestStop();started=false;}
	@Override public void stop(Runnable callback){worker.requestStop(callback);started=false;}
	@Override public boolean isRunning(){return started && worker.isRunning();}
}
