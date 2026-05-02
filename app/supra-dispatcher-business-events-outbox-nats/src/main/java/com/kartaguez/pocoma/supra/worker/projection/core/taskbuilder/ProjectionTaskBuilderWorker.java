package com.kartaguez.pocoma.supra.worker.projection.core.taskbuilder;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.BusinessEventEnvelope;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.BuildProjectionTasksUseCase;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimableWorkSource;
import com.kartaguez.pocoma.orchestrator.claimable.dispatcher.ClaimableWorkDispatcher;
import com.kartaguez.pocoma.orchestrator.claimable.dispatcher.ClaimableWorkDispatcherSettings;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWakeSignals;

public class ProjectionTaskBuilderWorker {

	private final ClaimableWorkDispatcher<BusinessEventEnvelope, PotId, String, ProjectionPartition> dispatcher;

	public ProjectionTaskBuilderWorker(
			ClaimableWorkSource<BusinessEventEnvelope, ProjectionPartition> workSource,
			BuildProjectionTasksUseCase buildProjectionTasksUseCase,
			ProjectionTaskBuilderSettings settings) {
		this(workSource, buildProjectionTasksUseCase, settings, WorkWakeBus.noop(), ignored -> true);
	}

	public ProjectionTaskBuilderWorker(
			ClaimableWorkSource<BusinessEventEnvelope, ProjectionPartition> workSource,
			BuildProjectionTasksUseCase buildProjectionTasksUseCase,
			ProjectionTaskBuilderSettings settings,
			WorkWakeBus<String, PotId> wakeBus,
			Predicate<PotId> wakeKeyPredicate) {
		Objects.requireNonNull(workSource, "workSource must not be null");
		Objects.requireNonNull(buildProjectionTasksUseCase, "buildProjectionTasksUseCase must not be null");
		Objects.requireNonNull(settings, "settings must not be null");
		Objects.requireNonNull(wakeBus, "wakeBus must not be null");
		Objects.requireNonNull(wakeKeyPredicate, "wakeKeyPredicate must not be null");
		this.dispatcher = new ClaimableWorkDispatcher<>(
				workSource,
				new SegmentedProjectionTaskBuilder(workSource, buildProjectionTasksUseCase, settings),
				BusinessEventEnvelope::potId,
				settings.partition(),
				toDispatcherSettings(settings),
				wakeBus,
				Set.of(ProjectionWakeSignals.BUSINESS_EVENTS_AVAILABLE),
				wakeKeyPredicate);
	}

	public void start() {
		dispatcher.start();
	}

	public void stop() {
		dispatcher.stop();
	}

	public boolean isRunning() {
		return dispatcher.isRunning();
	}

	int runOnce() {
		return dispatcher.runOnce();
	}

	private static ClaimableWorkDispatcherSettings toDispatcherSettings(ProjectionTaskBuilderSettings settings) {
		return new ClaimableWorkDispatcherSettings(
				settings.enabled(),
				settings.workerId(),
				settings.batchSize(),
				settings.leaseDuration(),
				settings.pollingInterval(),
				settings.wakeSignalsEnabled());
	}
}
