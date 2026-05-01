package com.kartaguez.pocoma.supra.worker.projection.core.taskbuilder;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.kartaguez.pocoma.engine.model.BusinessEventClaim;
import com.kartaguez.pocoma.engine.port.in.projection.intent.BuildProjectionTaskCommand;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.BuildProjectionTasksUseCase;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWorkerWakeBus;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWorkerWakeSignal;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.WakeablePollingLoop;

public class ProjectionTaskBuilderWorker {

	private static final System.Logger LOGGER = System.getLogger(ProjectionTaskBuilderWorker.class.getName());

	private final BuildProjectionTasksUseCase buildProjectionTasksUseCase;
	private final ProjectionTaskBuilderSettings settings;
	private final ProjectionWorkerWakeBus wakeBus;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private WakeablePollingLoop pollingLoop;
	private Thread thread;

	public ProjectionTaskBuilderWorker(
			BuildProjectionTasksUseCase buildProjectionTasksUseCase,
			ProjectionTaskBuilderSettings settings) {
		this(buildProjectionTasksUseCase, settings, ProjectionWorkerWakeBus.noop());
	}

	public ProjectionTaskBuilderWorker(
			BuildProjectionTasksUseCase buildProjectionTasksUseCase,
			ProjectionTaskBuilderSettings settings,
			ProjectionWorkerWakeBus wakeBus) {
		this.buildProjectionTasksUseCase = Objects.requireNonNull(
				buildProjectionTasksUseCase,
				"buildProjectionTasksUseCase must not be null");
		this.settings = Objects.requireNonNull(settings, "settings must not be null");
		this.wakeBus = Objects.requireNonNull(wakeBus, "wakeBus must not be null");
	}

	public void start() {
		if (!settings.enabled() || !running.compareAndSet(false, true)) {
			return;
		}
		pollingLoop = new WakeablePollingLoop(
				wakeBus,
				Set.of(ProjectionWorkerWakeSignal.BUSINESS_EVENTS_AVAILABLE),
				settings.partition(),
				settings.pollingInterval(),
				settings.wakeSignalsEnabled());
		thread = new Thread(this::runLoop, "pocoma-projection-task-builder-" + settings.workerId());
		thread.setDaemon(true);
		thread.start();
		LOGGER.log(System.Logger.Level.INFO, "Started projection task builder {0}", settings.workerId());
	}

	public void stop() {
		if (!running.compareAndSet(true, false)) {
			return;
		}
		if (pollingLoop != null) {
			pollingLoop.close();
		}
		thread.interrupt();
		try {
			thread.join(500);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
		LOGGER.log(System.Logger.Level.INFO, "Stopped projection task builder {0}", settings.workerId());
	}

	public boolean isRunning() {
		return running.get();
	}

	int runOnce() {
		List<BusinessEventClaim> claims = buildProjectionTasksUseCase.claimBusinessEvents(
				settings.batchSize(),
				settings.leaseDuration(),
				settings.workerId(),
				settings.partition());
		int builtTasks = 0;
		for (BusinessEventClaim claim : claims) {
			if (buildOrFail(claim)) {
				builtTasks++;
			}
		}
		if (builtTasks > 0) {
			for (BusinessEventClaim claim : claims) {
				wakeBus.publish(ProjectionWorkerWakeSignal.PROJECTION_TASKS_AVAILABLE, claim.event().potId());
			}
		}
		return builtTasks;
	}

	private boolean buildOrFail(BusinessEventClaim claim) {
		if (!buildProjectionTasksUseCase.markAccepted(claim.event().id(), claim.claimToken())) {
			return false;
		}
		try {
			if (!buildProjectionTasksUseCase.markRunning(claim.event().id(), claim.claimToken())) {
				buildProjectionTasksUseCase.release(claim.event().id(), claim.claimToken());
				return false;
			}
			buildProjectionTasksUseCase.buildProjectionTask(new BuildProjectionTaskCommand(claim.event()));
			if (!buildProjectionTasksUseCase.markDone(claim.event().id(), claim.claimToken())) {
				LOGGER.log(
						System.Logger.Level.WARNING,
						"Projection task built but outbox event completion was rejected for event " + claim.event().id());
			}
			return true;
		}
		catch (RuntimeException exception) {
			if (!buildProjectionTasksUseCase.markFailed(claim.event().id(), claim.claimToken(), exception.getMessage())) {
				LOGGER.log(
						System.Logger.Level.WARNING,
						"Projection task build failed but outbox event failure transition was rejected for event "
								+ claim.event().id());
			}
			LOGGER.log(
					System.Logger.Level.ERROR,
					"Failed to build projection task for outbox event " + claim.event().id(),
					exception);
			return false;
		}
	}

	private void runLoop() {
		while (running.get()) {
			try {
				while (running.get() && runOnce() > 0) {
				}
				if (running.get()) {
					pollingLoop.awaitWakeUp();
				}
			}
			catch (RuntimeException exception) {
				LOGGER.log(System.Logger.Level.ERROR, "Projection task builder loop failed", exception);
				pollingLoop.awaitWakeUp();
			}
		}
	}
}
