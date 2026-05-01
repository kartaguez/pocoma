package com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.kartaguez.pocoma.engine.model.ProjectionTaskClaim;
import com.kartaguez.pocoma.engine.model.ProjectionTaskDescriptor;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ExecuteProjectionTasksUseCase;
import com.kartaguez.pocoma.supra.worker.projection.core.model.ProjectionTask;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWorkerWakeBus;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWorkerWakeSignal;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.WakeablePollingLoop;

public class ProjectionTaskExecutorWorker {

	private static final System.Logger LOGGER = System.getLogger(ProjectionTaskExecutorWorker.class.getName());

	private final ExecuteProjectionTasksUseCase executeProjectionTasksUseCase;
	private final SegmentedProjectionTaskExecutor projectionWorker;
	private final ProjectionTaskExecutorWorkerSettings settings;
	private final ProjectionWorkerWakeBus wakeBus;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private WakeablePollingLoop pollingLoop;
	private Thread thread;

	public ProjectionTaskExecutorWorker(
			ExecuteProjectionTasksUseCase executeProjectionTasksUseCase,
			SegmentedProjectionTaskExecutor projectionWorker,
			ProjectionTaskExecutorWorkerSettings settings) {
		this(executeProjectionTasksUseCase, projectionWorker, settings, ProjectionWorkerWakeBus.noop());
	}

	public ProjectionTaskExecutorWorker(
			ExecuteProjectionTasksUseCase executeProjectionTasksUseCase,
			SegmentedProjectionTaskExecutor projectionWorker,
			ProjectionTaskExecutorWorkerSettings settings,
			ProjectionWorkerWakeBus wakeBus) {
		this.executeProjectionTasksUseCase = Objects.requireNonNull(
				executeProjectionTasksUseCase,
				"executeProjectionTasksUseCase must not be null");
		this.projectionWorker = Objects.requireNonNull(projectionWorker, "projectionWorker must not be null");
		this.settings = Objects.requireNonNull(settings, "settings must not be null");
		this.wakeBus = Objects.requireNonNull(wakeBus, "wakeBus must not be null");
	}

	public void start() {
		if (!settings.enabled() || !running.compareAndSet(false, true)) {
			return;
		}
		pollingLoop = new WakeablePollingLoop(
				wakeBus,
				Set.of(ProjectionWorkerWakeSignal.PROJECTION_TASKS_AVAILABLE, ProjectionWorkerWakeSignal.CAPACITY_AVAILABLE),
				settings.partition(),
				settings.pollingInterval(),
				settings.wakeSignalsEnabled());
		thread = new Thread(this::runLoop, "pocoma-projection-task-executor-" + settings.workerId());
		thread.setDaemon(true);
		thread.start();
		LOGGER.log(System.Logger.Level.INFO, "Started projection task executor {0}", settings.workerId());
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
		LOGGER.log(System.Logger.Level.INFO, "Stopped projection task executor {0}", settings.workerId());
	}

	public boolean isRunning() {
		return running.get();
	}

	int runOnce() {
		int availableCapacity = projectionWorker.availableCapacity();
		if (availableCapacity < 1) {
			return 0;
		}

		List<ProjectionTaskClaim> claims = executeProjectionTasksUseCase.claimProjectionTasks(
				Math.min(settings.batchSize(), availableCapacity),
				settings.leaseDuration(),
				settings.workerId(),
				settings.partition());
		int submittedTasks = 0;
		for (ProjectionTaskClaim claim : claims) {
			if (submitOrRelease(claim)) {
				submittedTasks++;
			}
		}
		return submittedTasks;
	}

	private boolean submitOrRelease(ProjectionTaskClaim claim) {
		ProjectionTaskDescriptor descriptor = claim.task();
		if (projectionWorker.availableCapacity(descriptor.potId()) < 1) {
			executeProjectionTasksUseCase.release(descriptor.id(), claim.claimToken());
			return false;
		}
		if (!executeProjectionTasksUseCase.markAccepted(descriptor.id(), claim.claimToken())) {
			return false;
		}
		ProjectionTask task = new ProjectionTask(
				descriptor.id(),
				claim.claimToken(),
				descriptor.potId(),
				descriptor.targetVersion(),
				descriptor.sourceEventType() == null ? descriptor.taskType().name() : descriptor.sourceEventType(),
				descriptor.traceId(),
				descriptor.commandCommittedAtNanos(),
				System.nanoTime());
		if (!projectionWorker.trySubmit(task)) {
			executeProjectionTasksUseCase.release(descriptor.id(), claim.claimToken());
			return false;
		}
		return true;
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
				LOGGER.log(System.Logger.Level.ERROR, "Projection task executor loop failed", exception);
				pollingLoop.awaitWakeUp();
			}
		}
	}
}
