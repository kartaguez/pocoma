package com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;
import com.kartaguez.pocoma.observability.api.NoopPocomaObservation;
import com.kartaguez.pocoma.observability.api.PocomaObservation;
import com.kartaguez.pocoma.observability.projection.ProjectionObservationContext;
import com.kartaguez.pocoma.supra.worker.projection.core.model.ProjectionTask;

public class DirectSegmentedProjectionWorker {

	private static final System.Logger LOGGER = System.getLogger(DirectSegmentedProjectionWorker.class.getName());

	private final ComputePotBalancesUseCase computePotBalancesUseCase;
	private final PocomaObservation observation;
	private final int threadCount;
	private final int maxRetries;
	private final Duration initialBackoff;
	private final Duration maxBackoff;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final List<Segment> segments;

	public DirectSegmentedProjectionWorker(
			ComputePotBalancesUseCase computePotBalancesUseCase,
			ProjectionTaskExecutorSettings settings) {
		this(computePotBalancesUseCase, settings, new NoopPocomaObservation());
	}

	public DirectSegmentedProjectionWorker(
			ComputePotBalancesUseCase computePotBalancesUseCase,
			ProjectionTaskExecutorSettings settings,
			PocomaObservation observation) {
		this.computePotBalancesUseCase = Objects.requireNonNull(
				computePotBalancesUseCase,
				"computePotBalancesUseCase must not be null");
		Objects.requireNonNull(settings, "settings must not be null");
		this.observation = Objects.requireNonNull(observation, "observation must not be null");
		this.threadCount = settings.threadCount();
		this.maxRetries = settings.maxRetries();
		this.initialBackoff = settings.initialBackoff();
		this.maxBackoff = settings.maxBackoff();
		this.segments = createSegments(settings.threadCount());
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
		LOGGER.log(System.Logger.Level.INFO, "Started direct projection worker with {0} segments", threadCount);
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
		LOGGER.log(System.Logger.Level.INFO, "Stopped direct projection worker");
	}

	public boolean isRunning() {
		return running.get();
	}

	private void runSegment(BlockingQueue<ProjectionTask> queue) {
		while (running.get() || !queue.isEmpty()) {
			try {
				processWithRetry(queue.take());
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
				computePotBalancesUseCase.computePotBalancesFull(task.potId(), task.targetVersion());
				observation.projectionSucceeded(context, startedAtNanos, System.nanoTime());
				return;
			}
			catch (RuntimeException exception) {
				if (attempt >= maxRetries) {
					observation.projectionFailed(context, startedAtNanos, System.nanoTime());
					LOGGER.log(
							System.Logger.Level.ERROR,
							"Direct projection task failed after " + (attempt + 1)
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
						"Direct projection task failed, retrying for potId=" + task.potId()
								+ ", targetVersion=" + task.targetVersion()
								+ ", sourceEventType=" + task.sourceEventType()
								+ ", attempt=" + attempt,
						exception);
				sleep(backoff);
				backoff = nextBackoff(backoff);
			}
		}
	}

	private Duration nextBackoff(Duration currentBackoff) {
		Duration doubled = currentBackoff.multipliedBy(2);
		if (doubled.compareTo(maxBackoff) > 0) {
			return maxBackoff;
		}
		return doubled;
	}

	private static void sleep(Duration backoff) throws InterruptedException {
		if (!backoff.isZero()) {
			Thread.sleep(backoff.toMillis());
		}
	}

	private List<Segment> createSegments(int threadCount) {
		List<Segment> createdSegments = new ArrayList<>(threadCount);
		for (int index = 0; index < threadCount; index++) {
			BlockingQueue<ProjectionTask> queue = new LinkedBlockingQueue<>();
			Thread thread = new Thread(() -> runSegment(queue), "pocoma-direct-projection-worker-" + index);
			thread.setDaemon(true);
			createdSegments.add(new Segment(queue, thread));
		}
		return List.copyOf(createdSegments);
	}

	private record Segment(BlockingQueue<ProjectionTask> queue, Thread thread) {
	}
}
