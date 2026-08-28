package com.kartaguez.pocoma.supra.worker.taskmaterialization.core;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.taskmaterialization.model.ConfiguredPipelineBinding;
import com.kartaguez.pocoma.engine.taskmaterialization.model.EventPipelineMaterializationCandidate;
import com.kartaguez.pocoma.engine.taskmaterialization.model.MaterializationResult;
import com.kartaguez.pocoma.engine.taskmaterialization.model.PipelineRegistry;
import com.kartaguez.pocoma.engine.taskmaterialization.port.in.MaterializeTasksCommand;
import com.kartaguez.pocoma.engine.taskmaterialization.port.in.MaterializeTasksUseCase;
import com.kartaguez.pocoma.orchestrator.claimable.polling.WakePollingRunner;
import com.kartaguez.pocoma.orchestrator.claimable.polling.WakePollingRunnerSettings;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;
import com.kartaguez.pocoma.supra.dispatcher.projection.shared.wakeup.ProjectionWakeSignals;

public final class EventToPipelineTaskMaterializerWorker {

	private final MaterializableEventSource eventSource;
	private final MaterializeTasksUseCase materializeTasksUseCase;
	private final PipelineRegistry pipelineRegistry;
	private final EventToPipelineTaskMaterializerSettings settings;
	private final Clock clock;
	private final PipelineMaterializationCompletedListener completedListener;
	private final TaskMaterializationObservation observation;
	private final WakePollingRunner<String, PotId> pollingRunner;

	public EventToPipelineTaskMaterializerWorker(
			MaterializableEventSource eventSource,
			MaterializeTasksUseCase materializeTasksUseCase,
			PipelineRegistry pipelineRegistry,
			EventToPipelineTaskMaterializerSettings settings) {
		this(
				eventSource,
				materializeTasksUseCase,
				pipelineRegistry,
				settings,
				Clock.systemUTC(),
				WorkWakeBus.noop(),
				ignored -> true,
				PipelineMaterializationCompletedListener.noop(),
				new NoopTaskMaterializationObservation());
	}

	public EventToPipelineTaskMaterializerWorker(
			MaterializableEventSource eventSource,
			MaterializeTasksUseCase materializeTasksUseCase,
			PipelineRegistry pipelineRegistry,
			EventToPipelineTaskMaterializerSettings settings,
			Clock clock,
			WorkWakeBus<String, PotId> wakeBus,
			Predicate<PotId> wakeKeyPredicate) {
		this(
				eventSource,
				materializeTasksUseCase,
				pipelineRegistry,
				settings,
				clock,
				wakeBus,
				wakeKeyPredicate,
				PipelineMaterializationCompletedListener.noop(),
				new NoopTaskMaterializationObservation());
	}

	public EventToPipelineTaskMaterializerWorker(
			MaterializableEventSource eventSource,
			MaterializeTasksUseCase materializeTasksUseCase,
			PipelineRegistry pipelineRegistry,
			EventToPipelineTaskMaterializerSettings settings,
			Clock clock,
			WorkWakeBus<String, PotId> wakeBus,
			Predicate<PotId> wakeKeyPredicate,
			PipelineMaterializationCompletedListener completedListener) {
		this(
				eventSource,
				materializeTasksUseCase,
				pipelineRegistry,
				settings,
				clock,
				wakeBus,
				wakeKeyPredicate,
				completedListener,
				new NoopTaskMaterializationObservation());
	}

	public EventToPipelineTaskMaterializerWorker(
			MaterializableEventSource eventSource,
			MaterializeTasksUseCase materializeTasksUseCase,
			PipelineRegistry pipelineRegistry,
			EventToPipelineTaskMaterializerSettings settings,
			Clock clock,
			WorkWakeBus<String, PotId> wakeBus,
			Predicate<PotId> wakeKeyPredicate,
			PipelineMaterializationCompletedListener completedListener,
			TaskMaterializationObservation observation) {
		this.eventSource = Objects.requireNonNull(
				eventSource,
				"eventSource must not be null");
		this.materializeTasksUseCase = Objects.requireNonNull(
				materializeTasksUseCase,
				"materializeTasksUseCase must not be null");
		this.pipelineRegistry = Objects.requireNonNull(pipelineRegistry, "pipelineRegistry must not be null");
		this.settings = Objects.requireNonNull(settings, "settings must not be null");
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
		Objects.requireNonNull(wakeBus, "wakeBus must not be null");
		Objects.requireNonNull(wakeKeyPredicate, "wakeKeyPredicate must not be null");
		this.completedListener = Objects.requireNonNull(
				completedListener,
				"completedListener must not be null");
		this.observation = Objects.requireNonNull(observation, "observation must not be null");
		this.pollingRunner = new WakePollingRunner<>(
				this::runOnce,
				processed -> processed > 0,
				new WakePollingRunnerSettings(
						settings.enabled(),
						settings.workerId(),
						settings.pollingInterval(),
						settings.wakeSignalsEnabled()),
				wakeBus,
				Set.of(ProjectionWakeSignals.BUSINESS_EVENTS_AVAILABLE),
				wakeKeyPredicate);
	}

	public void start() {
		pollingRunner.start();
	}

	public void stop() {
		pollingRunner.stop();
	}

	public boolean isRunning() {
		return pollingRunner.isRunning();
	}

	public int runOnce() {
		long runStartedAtNanos = System.nanoTime();
		List<ConfiguredPipelineBinding> activeBindings = pipelineRegistry.activeBindings();
		if (activeBindings.isEmpty()) {
			observation.runCompleted(new TaskMaterializationRunObservation(
					settings.workerId(),
					settings.partition(),
					0,
					0,
					System.nanoTime() - runStartedAtNanos));
			return 0;
		}
		Instant upperBound = clock.instant().minus(settings.safetyDelay());
		List<EventPipelineMaterializationCandidate> candidates = eventSource.findUnmaterializedEventPipelinePairs(
				settings.batchSize(),
				settings.partition(),
				upperBound,
				activeBindings);
		for (EventPipelineMaterializationCandidate candidate : candidates) {
			long materializationStartedAtNanos = System.nanoTime();
			MaterializationResult result = materializeTasksUseCase.materializeTasks(new MaterializeTasksCommand(
					candidate.event(),
					candidate.pipeline()));
			completedListener.onMaterializationCompleted(candidate, result);
			observation.materializationCompleted(
					new TaskMaterializationEventObservation(
							candidate,
							clock.instant(),
							System.nanoTime() - materializationStartedAtNanos),
					result);
		}
		observation.runCompleted(new TaskMaterializationRunObservation(
				settings.workerId(),
				settings.partition(),
				activeBindings.size(),
				candidates.size(),
				System.nanoTime() - runStartedAtNanos));
		return candidates.size();
	}
}
