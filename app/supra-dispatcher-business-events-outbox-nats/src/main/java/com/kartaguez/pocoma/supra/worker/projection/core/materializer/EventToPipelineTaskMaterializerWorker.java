package com.kartaguez.pocoma.supra.worker.projection.core.materializer;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.pipeline.EventPipelineMaterializationCandidate;
import com.kartaguez.pocoma.engine.model.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.model.pipeline.PipelineRegistry;
import com.kartaguez.pocoma.engine.port.out.persistence.pipeline.PipelineMaterializationPort;
import com.kartaguez.pocoma.engine.service.projection.pipeline.MaterializeEventForPipelineService;
import com.kartaguez.pocoma.orchestrator.claimable.polling.WakePollingRunner;
import com.kartaguez.pocoma.orchestrator.claimable.polling.WakePollingRunnerSettings;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;
import com.kartaguez.pocoma.supra.dispatcher.projection.shared.wakeup.ProjectionWakeSignals;

public final class EventToPipelineTaskMaterializerWorker {

	private final PipelineMaterializationPort materializationPort;
	private final MaterializeEventForPipelineService materializationService;
	private final PipelineRegistry pipelineRegistry;
	private final EventToPipelineTaskMaterializerSettings settings;
	private final Clock clock;
	private final WakePollingRunner<String, PotId> pollingRunner;

	public EventToPipelineTaskMaterializerWorker(
			PipelineMaterializationPort materializationPort,
			MaterializeEventForPipelineService materializationService,
			PipelineRegistry pipelineRegistry,
			EventToPipelineTaskMaterializerSettings settings) {
		this(
				materializationPort,
				materializationService,
				pipelineRegistry,
				settings,
				Clock.systemUTC(),
				WorkWakeBus.noop(),
				ignored -> true);
	}

	public EventToPipelineTaskMaterializerWorker(
			PipelineMaterializationPort materializationPort,
			MaterializeEventForPipelineService materializationService,
			PipelineRegistry pipelineRegistry,
			EventToPipelineTaskMaterializerSettings settings,
			Clock clock,
			WorkWakeBus<String, PotId> wakeBus,
			Predicate<PotId> wakeKeyPredicate) {
		this.materializationPort = Objects.requireNonNull(
				materializationPort,
				"materializationPort must not be null");
		this.materializationService = Objects.requireNonNull(
				materializationService,
				"materializationService must not be null");
		this.pipelineRegistry = Objects.requireNonNull(pipelineRegistry, "pipelineRegistry must not be null");
		this.settings = Objects.requireNonNull(settings, "settings must not be null");
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
		Objects.requireNonNull(wakeBus, "wakeBus must not be null");
		Objects.requireNonNull(wakeKeyPredicate, "wakeKeyPredicate must not be null");
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
		List<PipelineDefinition> activePipelines = pipelineRegistry.activePipelines();
		if (activePipelines.isEmpty()) {
			return 0;
		}
		Instant upperBound = clock.instant().minus(settings.safetyDelay());
		List<EventPipelineMaterializationCandidate> candidates = materializationPort.findUnmaterializedEventPipelinePairs(
				settings.batchSize(),
				settings.partition(),
				upperBound,
				activePipelines);
		for (EventPipelineMaterializationCandidate candidate : candidates) {
			materializationService.materialize(candidate);
		}
		return candidates.size();
	}
}
