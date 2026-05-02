package com.kartaguez.pocoma.orchestrator.claimable.dispatcher;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import com.kartaguez.pocoma.orchestrator.claimable.pool.SegmentedWorkHandler;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WakeSignalWaiter;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimWorkRequest;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimableWorkSource;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimedWork;
import com.kartaguez.pocoma.orchestrator.claimable.work.WorkKeyResolver;

public class ClaimableWorkDispatcher<W, K, S, C> {

	private static final System.Logger LOGGER = System.getLogger(ClaimableWorkDispatcher.class.getName());

	private final ClaimableWorkSource<W, C> workSource;
	private final SegmentedWorkHandler<ClaimedWork<W>, K> workHandler;
	private final WorkKeyResolver<W, K> keyResolver;
	private final C claimCriteria;
	private final ClaimableWorkDispatcherSettings settings;
	private final WorkWakeBus<S, K> wakeBus;
	private final Set<S> wakeSignals;
	private final Predicate<K> wakeKeyPredicate;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private WakeSignalWaiter<S, K> pollingLoop;
	private Thread thread;

	public ClaimableWorkDispatcher(
			ClaimableWorkSource<W, C> workSource,
			SegmentedWorkHandler<ClaimedWork<W>, K> workHandler,
			WorkKeyResolver<W, K> keyResolver,
			C claimCriteria,
			ClaimableWorkDispatcherSettings settings,
			WorkWakeBus<S, K> wakeBus,
			Set<S> wakeSignals,
			Predicate<K> wakeKeyPredicate) {
		this.workSource = Objects.requireNonNull(workSource, "workSource must not be null");
		this.workHandler = Objects.requireNonNull(workHandler, "workHandler must not be null");
		this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver must not be null");
		this.claimCriteria = claimCriteria;
		this.settings = Objects.requireNonNull(settings, "settings must not be null");
		this.wakeBus = Objects.requireNonNull(wakeBus, "wakeBus must not be null");
		this.wakeSignals = Set.copyOf(Objects.requireNonNull(wakeSignals, "wakeSignals must not be null"));
		if (this.wakeSignals.isEmpty()) {
			throw new IllegalArgumentException("wakeSignals must not be empty");
		}
		this.wakeKeyPredicate = Objects.requireNonNull(wakeKeyPredicate, "wakeKeyPredicate must not be null");
	}

	public void start() {
		if (!settings.enabled() || !running.compareAndSet(false, true)) {
			return;
		}
		workHandler.start();
		pollingLoop = new WakeSignalWaiter<>(
				wakeBus,
				wakeSignals,
				wakeKeyPredicate,
				settings.pollingInterval(),
				settings.wakeSignalsEnabled());
		thread = new Thread(this::runLoop, "pocoma-claimable-work-dispatcher-" + settings.workerId());
		thread.setDaemon(true);
		thread.start();
		LOGGER.log(System.Logger.Level.INFO, "Started claimable work dispatcher {0}", settings.workerId());
	}

	public void stop() {
		if (!running.compareAndSet(true, false)) {
			return;
		}
		if (pollingLoop != null) {
			pollingLoop.close();
		}
		if (thread != null) {
			thread.interrupt();
			try {
				thread.join(500);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		}
		workHandler.stop();
		LOGGER.log(System.Logger.Level.INFO, "Stopped claimable work dispatcher {0}", settings.workerId());
	}

	public boolean isRunning() {
		return running.get();
	}

	public int runOnce() {
		int availableCapacity = workHandler.availableCapacity();
		if (availableCapacity < 1) {
			return 0;
		}
		var claimedWorks = workSource.claim(new ClaimWorkRequest<>(
				Math.min(settings.batchSize(), availableCapacity),
				settings.leaseDuration(),
				settings.workerId(),
				claimCriteria));
		int submittedWorks = 0;
		for (ClaimedWork<W> work : claimedWorks) {
			if (submitOrRelease(work)) {
				submittedWorks++;
			}
		}
		return submittedWorks;
	}

	private boolean submitOrRelease(ClaimedWork<W> work) {
		K key = keyResolver.keyOf(work.instruction());
		if (workHandler.availableCapacity(key) < 1) {
			workSource.release(work);
			return false;
		}
		if (!workSource.markAccepted(work)) {
			return false;
		}
		if (!workHandler.trySubmit(work)) {
			workSource.release(work);
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
				LOGGER.log(System.Logger.Level.ERROR, "Claimable work dispatcher loop failed", exception);
				pollingLoop.awaitWakeUp();
			}
		}
	}
}
