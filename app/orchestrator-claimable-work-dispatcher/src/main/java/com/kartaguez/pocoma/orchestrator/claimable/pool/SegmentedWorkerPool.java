package com.kartaguez.pocoma.orchestrator.claimable.pool;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimableWorkLifecycle;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimedWork;
import com.kartaguez.pocoma.orchestrator.claimable.work.WorkHandler;

public class SegmentedWorkerPool<T, K> implements SegmentedWorkHandler<ClaimedWork<T>, K> {

	private static final System.Logger LOGGER = System.getLogger(SegmentedWorkerPool.class.getName());

	private final ClaimableWorkLifecycle<T, ?> workSource;
	private final WorkHandler<T> workHandler;
	private final WorkSegmenter<T, K> segmenter;
	private final SegmentedWorkerPoolSettings settings;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final List<Segment<T>> segments;

	public SegmentedWorkerPool(
			ClaimableWorkLifecycle<T, ?> workSource,
			WorkHandler<T> workHandler,
			WorkSegmenter<T, K> segmenter,
			SegmentedWorkerPoolSettings settings) {
		this.workSource = Objects.requireNonNull(workSource, "workSource must not be null");
		this.workHandler = Objects.requireNonNull(workHandler, "workHandler must not be null");
		this.segmenter = Objects.requireNonNull(segmenter, "segmenter must not be null");
		this.settings = Objects.requireNonNull(settings, "settings must not be null");
		this.segments = createSegments(settings.threadCount(), settings.queueCapacity());
	}

	@Override
	public boolean trySubmit(ClaimedWork<T> work) {
		Objects.requireNonNull(work, "work must not be null");
		if (!running.get()) {
			throw new IllegalStateException("Segmented worker pool is not running");
		}
		return segments.get(segmentIndex(work.instruction())).queue.offer(work);
	}

	@Override
	public int availableCapacity() {
		long capacity = segments.stream()
				.mapToLong(segment -> segment.queue.remainingCapacity())
				.sum();
		return capacity > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) capacity;
	}

	@Override
	public int availableCapacity(K key) {
		return segments.get(segmentIndexForKey(key)).queue.remainingCapacity();
	}

	public int segmentIndex(T instruction) {
		Objects.requireNonNull(instruction, "instruction must not be null");
		return segmentIndexForKey(segmenter.segmentKey(instruction));
	}

	public int segmentIndexForKey(K key) {
		Objects.requireNonNull(key, "key must not be null");
		return Math.floorMod(key.hashCode(), settings.threadCount());
	}

	@Override
	public void start() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		for (Segment<T> segment : segments) {
			segment.thread.start();
		}
		LOGGER.log(System.Logger.Level.INFO, "Started segmented worker pool {0}", settings.workerName());
	}

	@Override
	public void stop() {
		if (!running.compareAndSet(true, false)) {
			return;
		}
		for (Segment<T> segment : segments) {
			segment.thread.interrupt();
		}
		for (Segment<T> segment : segments) {
			try {
				segment.thread.join(500);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		LOGGER.log(System.Logger.Level.INFO, "Stopped segmented worker pool {0}", settings.workerName());
	}

	@Override
	public boolean isRunning() {
		return running.get();
	}

	private void runSegment(BlockingQueue<ClaimedWork<T>> queue) {
		while (running.get() || !queue.isEmpty()) {
			try {
				ClaimedWork<T> work = queue.take();
				processWithRetry(work);
			}
			catch (InterruptedException exception) {
				if (!running.get()) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	private void processWithRetry(ClaimedWork<T> work) throws InterruptedException {
		if (!workSource.markProcessing(work)) {
			return;
		}
		Heartbeat heartbeat = startHeartbeat(work);
		int attempt = 0;
		Duration backoff = settings.initialBackoff();
		try {
			while (true) {
				try {
					workHandler.handle(work.instruction());
					if (heartbeat.ownershipLost()) {
						return;
					}
					workSource.markDone(work);
					return;
				}
				catch (RuntimeException exception) {
					if (heartbeat.ownershipLost()) {
						return;
					}
					if (attempt >= settings.maxRetries()) {
						workSource.markFailed(work, exception);
						LOGGER.log(
								System.Logger.Level.ERROR,
								"Claimed work failed after " + (attempt + 1) + " attempts",
								exception);
						return;
					}
					attempt++;
					LOGGER.log(System.Logger.Level.WARNING, "Claimed work failed, retrying attempt " + attempt, exception);
					sleep(backoff);
					backoff = nextBackoff(backoff);
				}
			}
		}
		finally {
			heartbeat.stop();
		}
	}

	private Heartbeat startHeartbeat(ClaimedWork<T> work) {
		Heartbeat heartbeat = new Heartbeat(work);
		heartbeat.start();
		return heartbeat;
	}

	private Duration nextBackoff(Duration currentBackoff) {
		Duration doubled = currentBackoff.multipliedBy(2);
		if (doubled.compareTo(settings.maxBackoff()) > 0) {
			return settings.maxBackoff();
		}
		return doubled;
	}

	private static void sleep(Duration backoff) throws InterruptedException {
		if (!backoff.isZero()) {
			Thread.sleep(backoff.toMillis());
		}
	}

	private List<Segment<T>> createSegments(int threadCount, int queueCapacity) {
		List<Segment<T>> createdSegments = new ArrayList<>(threadCount);
		for (int index = 0; index < threadCount; index++) {
			BlockingQueue<ClaimedWork<T>> queue = new LinkedBlockingQueue<>(queueCapacity);
			Thread thread = new Thread(() -> runSegment(queue), settings.workerName() + "-" + index);
			thread.setDaemon(true);
			createdSegments.add(new Segment<>(queue, thread));
		}
		return List.copyOf(createdSegments);
	}

	private record Segment<T>(BlockingQueue<ClaimedWork<T>> queue, Thread thread) {
	}

	private final class Heartbeat {
		private final ClaimedWork<T> work;
		private final AtomicBoolean active = new AtomicBoolean(true);
		private final AtomicBoolean ownershipLost = new AtomicBoolean(false);
		private final Thread thread;
		private final Thread processingThread;

		private Heartbeat(ClaimedWork<T> work) {
			this.work = work;
			this.processingThread = Thread.currentThread();
			this.thread = new Thread(this::run, settings.workerName() + "-heartbeat");
			this.thread.setDaemon(true);
		}

		private void start() {
			thread.start();
		}

		private boolean ownershipLost() {
			return ownershipLost.get();
		}

		private void stop() {
			active.set(false);
			thread.interrupt();
			try {
				thread.join(500);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		}

		private void run() {
			while (active.get()) {
				try {
					sleep(settings.heartbeatInterval());
					if (active.get() && !workSource.heartbeat(work, settings.leaseDuration())) {
						ownershipLost.set(true);
						active.set(false);
						processingThread.interrupt();
					}
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					return;
				}
				catch (RuntimeException exception) {
					ownershipLost.set(true);
					active.set(false);
					LOGGER.log(System.Logger.Level.WARNING, "Claimed work heartbeat failed", exception);
				}
			}
		}
	}
}
