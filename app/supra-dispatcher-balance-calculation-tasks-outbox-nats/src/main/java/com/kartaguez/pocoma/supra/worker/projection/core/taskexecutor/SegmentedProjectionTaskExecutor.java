package com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor;

import com.kartaguez.pocoma.supra.dispatcher.projection.shared.taskexecutor.ProjectionTaskExecutorSettings;

import java.util.Objects;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.projection.intent.ExecuteProjectionTaskCommand;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ExecuteProjectionTasksUseCase;
import com.kartaguez.pocoma.observability.api.NoopPocomaObservation;
import com.kartaguez.pocoma.observability.api.PocomaObservation;
import com.kartaguez.pocoma.observability.projection.ProjectionObservationContext;
import com.kartaguez.pocoma.orchestrator.claimable.wake.CapacityNotifier;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimableWorkLifecycle;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimedWork;
import com.kartaguez.pocoma.orchestrator.claimable.pool.SegmentedWorkerPool;
import com.kartaguez.pocoma.orchestrator.claimable.pool.SegmentedWorkerPoolSettings;
import com.kartaguez.pocoma.supra.dispatcher.projection.shared.model.ProjectionTask;

public class SegmentedProjectionTaskExecutor {

	private static final System.Logger LOGGER = System.getLogger(SegmentedProjectionTaskExecutor.class.getName());

	private final PocomaObservation observation;
	private final CapacityNotifier<PotId> capacityNotifier;
	private final SegmentedWorkerPool<ProjectionTask, PotId> workerPool;

	public SegmentedProjectionTaskExecutor(
			ExecuteProjectionTasksUseCase executeProjectionTasksUseCase,
			ProjectionTaskExecutorSettings settings) {
		this(noopWorkSource(), executeProjectionTasksUseCase, settings, new NoopPocomaObservation());
	}

	public SegmentedProjectionTaskExecutor(
			ExecuteProjectionTasksUseCase executeProjectionTasksUseCase,
			ProjectionTaskExecutorSettings settings,
			PocomaObservation observation) {
		this(noopWorkSource(), executeProjectionTasksUseCase, settings, observation);
	}

	public SegmentedProjectionTaskExecutor(
			ClaimableWorkLifecycle<ProjectionTask, ?> workSource,
			ExecuteProjectionTasksUseCase executeProjectionTasksUseCase,
			ProjectionTaskExecutorSettings settings,
			PocomaObservation observation) {
		this(workSource, executeProjectionTasksUseCase, settings, observation, CapacityNotifier.noop());
	}

	public SegmentedProjectionTaskExecutor(
			ClaimableWorkLifecycle<ProjectionTask, ?> workSource,
			ExecuteProjectionTasksUseCase executeProjectionTasksUseCase,
			ProjectionTaskExecutorSettings settings,
			PocomaObservation observation,
			CapacityNotifier<PotId> capacityNotifier) {
		Objects.requireNonNull(workSource, "workSource must not be null");
		Objects.requireNonNull(executeProjectionTasksUseCase, "executeProjectionTasksUseCase must not be null");
		this.observation = Objects.requireNonNull(observation, "observation must not be null");
		this.capacityNotifier = Objects.requireNonNull(capacityNotifier, "capacityNotifier must not be null");
		Objects.requireNonNull(settings, "settings must not be null");
		this.workerPool = new SegmentedWorkerPool<>(
				workSource,
				task -> process(task, executeProjectionTasksUseCase),
				ProjectionTask::potId,
				new SegmentedWorkerPoolSettings(
						"pocoma-projection-worker",
						settings.threadCount(),
						settings.queueCapacity(),
						settings.maxRetries(),
						settings.initialBackoff(),
						settings.maxBackoff(),
						settings.leaseDuration(),
						settings.heartbeatInterval()));
	}

	public void submit(ProjectionTask task) {
		Objects.requireNonNull(task, "task must not be null");
		if (!trySubmit(task)) {
			throw new IllegalStateException("Projection task queue is full");
		}
	}

	public boolean trySubmit(ProjectionTask task) {
		Objects.requireNonNull(task, "task must not be null");
		boolean accepted = workerPool.trySubmit(new ClaimedWork<>(task));
		if (accepted) {
			observation.eventSubmitted(task.toObservationContext(), task.eventSubmittedAtNanos());
		}
		return accepted;
	}

	public int availableCapacity() {
		return workerPool.availableCapacity();
	}

	public int availableCapacity(PotId potId) {
		return workerPool.availableCapacity(potId);
	}

	public int segmentIndex(PotId potId) {
		return workerPool.segmentIndexForKey(potId);
	}

	public void start() {
		workerPool.start();
	}

	public void stop() {
		workerPool.stop();
		LOGGER.log(System.Logger.Level.INFO, "Stopped projection worker");
	}

	public boolean isRunning() {
		return workerPool.isRunning();
	}

	private void process(ProjectionTask task, ExecuteProjectionTasksUseCase executeProjectionTasksUseCase) {
		ProjectionObservationContext context = task.toObservationContext();
		long startedAtNanos = System.nanoTime();
		try (PocomaObservation.Scope ignored = observation.openProjectionScope(context)) {
			observation.projectionStarted(context, startedAtNanos);
			executeProjectionTasksUseCase.executeProjectionTask(new ExecuteProjectionTaskCommand(
					task.potId(),
					task.targetVersion()));
			observation.projectionSucceeded(context, startedAtNanos, System.nanoTime());
		}
		catch (RuntimeException exception) {
			observation.projectionFailed(context, startedAtNanos, System.nanoTime());
			throw exception;
		}
		finally {
			capacityNotifier.notifyCapacityAvailable(task.potId());
		}
	}

	private static ClaimableWorkLifecycle<ProjectionTask, Object> noopWorkSource() {
		return new ClaimableWorkLifecycle<>() {
			@Override
			public java.util.List<ClaimedWork<ProjectionTask>> claim(
					com.kartaguez.pocoma.orchestrator.claimable.work.ClaimWorkRequest<Object> request) {
				return java.util.List.of();
			}

			@Override
			public boolean markAccepted(ClaimedWork<ProjectionTask> work) {
				return true;
			}

			@Override
			public void release(ClaimedWork<ProjectionTask> work) {
			}

			@Override
			public boolean markProcessing(ClaimedWork<ProjectionTask> work) {
				return true;
			}

			@Override
			public boolean heartbeat(ClaimedWork<ProjectionTask> work, java.time.Duration leaseDuration) {
				return true;
			}

			@Override
			public boolean markDone(ClaimedWork<ProjectionTask> work) {
				return true;
			}

			@Override
			public boolean markFailed(ClaimedWork<ProjectionTask> work, RuntimeException error) {
				return true;
			}
		};
	}
}
