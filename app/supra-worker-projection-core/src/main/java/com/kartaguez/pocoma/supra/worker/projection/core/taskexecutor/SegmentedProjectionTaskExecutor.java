package com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.projection.intent.ExecuteProjectionTaskCommand;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ExecuteProjectionTasksUseCase;
import com.kartaguez.pocoma.observability.api.NoopPocomaObservation;
import com.kartaguez.pocoma.observability.api.PocomaObservation;
import com.kartaguez.pocoma.observability.projection.ProjectionObservationContext;
import com.kartaguez.pocoma.supra.worker.projection.core.model.ProjectionTask;

public class SegmentedProjectionTaskExecutor {

	private static final System.Logger LOGGER = System.getLogger(SegmentedProjectionTaskExecutor.class.getName());

	private final ExecuteProjectionTasksUseCase executeProjectionTasksUseCase;
	private final PocomaObservation observation;
	private final int threadCount;
	private final int maxRetries;
	private final Duration initialBackoff;
	private final Duration maxBackoff;
	private final Duration capacityWakeupMinInterval;
	private final java.util.function.Consumer<PotId> capacityAvailableNotifier;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicLong lastCapacityWakeupNanos = new AtomicLong(Long.MIN_VALUE);
	private final List<Segment> segments;

	public SegmentedProjectionTaskExecutor(
			ExecuteProjectionTasksUseCase executeProjectionTasksUseCase,
			ProjectionTaskExecutorSettings settings) {
		this(executeProjectionTasksUseCase, settings, new NoopPocomaObservation());
	}

	public SegmentedProjectionTaskExecutor(
			ExecuteProjectionTasksUseCase executeProjectionTasksUseCase,
			ProjectionTaskExecutorSettings settings,
			PocomaObservation observation) {
		this(executeProjectionTasksUseCase, settings, observation, ignored -> {
		});
	}

	public SegmentedProjectionTaskExecutor(
			ExecuteProjectionTasksUseCase executeProjectionTasksUseCase,
			ProjectionTaskExecutorSettings settings,
			PocomaObservation observation,
			java.util.function.Consumer<PotId> capacityAvailableNotifier) {
		this.executeProjectionTasksUseCase = Objects.requireNonNull(
				executeProjectionTasksUseCase,
				"executeProjectionTasksUseCase must not be null");
		this.observation = Objects.requireNonNull(observation, "observation must not be null");
		this.capacityAvailableNotifier = Objects.requireNonNull(
				capacityAvailableNotifier,
				"capacityAvailableNotifier must not be null");
		Objects.requireNonNull(settings, "settings must not be null");
		this.threadCount = settings.threadCount();
		this.maxRetries = settings.maxRetries();
		this.initialBackoff = settings.initialBackoff();
		this.maxBackoff = settings.maxBackoff();
		this.capacityWakeupMinInterval = settings.capacityWakeupMinInterval();
		this.segments = createSegments(settings.threadCount(), settings.queueCapacity());
	}

	public void submit(ProjectionTask task) {
		Objects.requireNonNull(task, "task must not be null");
		if (!running.get()) {
			throw new IllegalStateException("Projection worker is not running");
		}

		try {
			segments.get(segmentIndex(task.potId())).queue.put(task);
			observation.eventSubmitted(task.toObservationContext(), task.eventSubmittedAtNanos());
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Projection task submission was interrupted", exception);
		}
	}

	public boolean trySubmit(ProjectionTask task) {
		Objects.requireNonNull(task, "task must not be null");
		if (!running.get()) {
			throw new IllegalStateException("Projection worker is not running");
		}

		boolean accepted = segments.get(segmentIndex(task.potId())).queue.offer(task);
		if (accepted) {
			observation.eventSubmitted(task.toObservationContext(), task.eventSubmittedAtNanos());
		}
		return accepted;
	}

	public int availableCapacity() {
		return segments.stream()
				.mapToInt(segment -> segment.queue.remainingCapacity())
				.sum();
	}

	public int availableCapacity(PotId potId) {
		return segments.get(segmentIndex(potId)).queue.remainingCapacity();
	}

	public int segmentIndex(PotId potId) {
		Objects.requireNonNull(potId, "potId must not be null");
		return Math.floorMod(potId.hashCode(), threadCount);
	}

	public void start() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		for (Segment segment : segments) {
			segment.thread.start();
		}
		LOGGER.log(System.Logger.Level.INFO, "Started projection worker with {0} segments", threadCount);
	}

	public void stop() {
		if (!running.compareAndSet(true, false)) {
			return;
		}
		for (Segment segment : segments) {
			segment.thread.interrupt();
		}
		for (Segment segment : segments) {
			try {
				segment.thread.join(500);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		LOGGER.log(System.Logger.Level.INFO, "Stopped projection worker");
	}

	public boolean isRunning() {
		return running.get();
	}

	private void runSegment(BlockingQueue<ProjectionTask> queue) {
		while (running.get() || !queue.isEmpty()) {
			try {
				ProjectionTask task = queue.take();
				notifyCapacityAvailable(task.potId());
				try {
					processWithRetry(task);
				}
				finally {
					notifyCapacityAvailable(task.potId());
				}
			}
			catch (InterruptedException exception) {
				if (!running.get()) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	private void processWithRetry(ProjectionTask task) throws InterruptedException {
		int attempt = 0;
		Duration backoff = initialBackoff;
		ProjectionObservationContext context = task.toObservationContext();
		while (true) {
			long startedAtNanos = System.nanoTime();
			try (PocomaObservation.Scope ignored = observation.openProjectionScope(context)) {
				observation.projectionStarted(context, startedAtNanos);
				markRunning(task);
				executeProjectionTasksUseCase.executeProjectionTask(
						new ExecuteProjectionTaskCommand(task.potId(), task.targetVersion()));
				observation.projectionSucceeded(context, startedAtNanos, System.nanoTime());
				markDone(task);
				return;
			}
			catch (RuntimeException exception) {
				if (attempt >= maxRetries) {
					observation.projectionFailed(context, startedAtNanos, System.nanoTime());
					markFailed(task, exception);
					LOGGER.log(
							System.Logger.Level.ERROR,
							"Projection task failed after " + (attempt + 1)
									+ " attempts for potId=" + task.potId()
									+ ", targetVersion=" + task.targetVersion()
									+ ", sourceEventType=" + task.sourceEventType(),
							exception);
					return;
				}
				attempt++;
				observation.projectionRetry(context, attempt);
				LOGGER.log(
						System.Logger.Level.WARNING,
						"Projection task failed, retrying for potId=" + task.potId()
								+ ", targetVersion=" + task.targetVersion()
								+ ", sourceEventType=" + task.sourceEventType()
								+ ", attempt=" + attempt,
						exception);
				sleep(backoff);
				backoff = nextBackoff(backoff);
			}
		}
	}

	private void markRunning(ProjectionTask task) {
		if (task.taskId() != null && task.claimToken() != null) {
			executeProjectionTasksUseCase.markRunning(task.taskId(), task.claimToken());
		}
	}

	private void markDone(ProjectionTask task) {
		if (task.taskId() != null && task.claimToken() != null) {
			executeProjectionTasksUseCase.markDone(task.taskId(), task.claimToken());
		}
	}

	private void markFailed(ProjectionTask task, RuntimeException exception) {
		if (task.taskId() != null && task.claimToken() != null) {
			executeProjectionTasksUseCase.markFailed(task.taskId(), task.claimToken(), exception.getMessage());
		}
	}

	private Duration nextBackoff(Duration currentBackoff) {
		Duration doubled = currentBackoff.multipliedBy(2);
		if (doubled.compareTo(maxBackoff) > 0) {
			return maxBackoff;
		}
		return doubled;
	}

	private void notifyCapacityAvailable(PotId potId) {
		long now = System.nanoTime();
		long minIntervalNanos = capacityWakeupMinInterval.toNanos();
		while (true) {
			long previous = lastCapacityWakeupNanos.get();
			if (previous != Long.MIN_VALUE && now - previous < minIntervalNanos) {
				return;
			}
			if (lastCapacityWakeupNanos.compareAndSet(previous, now)) {
				try {
					capacityAvailableNotifier.accept(potId);
				}
				catch (RuntimeException exception) {
					LOGGER.log(System.Logger.Level.WARNING, "Capacity wake signal failed", exception);
				}
				return;
			}
		}
	}

	private static void sleep(Duration backoff) throws InterruptedException {
		if (!backoff.isZero()) {
			Thread.sleep(backoff.toMillis());
		}
	}

	private List<Segment> createSegments(int threadCount, int queueCapacity) {
		List<Segment> createdSegments = new ArrayList<>(threadCount);
		for (int index = 0; index < threadCount; index++) {
			BlockingQueue<ProjectionTask> queue = new LinkedBlockingQueue<>(queueCapacity);
			Thread thread = new Thread(() -> runSegment(queue), "pocoma-projection-worker-" + index);
			thread.setDaemon(true);
			createdSegments.add(new Segment(queue, thread));
		}
		return List.copyOf(createdSegments);
	}

	private record Segment(BlockingQueue<ProjectionTask> queue, Thread thread) {
	}
}
