package com.kartaguez.pocoma.supra.worker.projection.spring;

import java.util.Objects;

import org.springframework.context.event.EventListener;

import com.kartaguez.pocoma.engine.event.ExpenseCreatedEvent;
import com.kartaguez.pocoma.engine.event.ExpenseDeletedEvent;
import com.kartaguez.pocoma.engine.event.ExpenseDetailsUpdatedEvent;
import com.kartaguez.pocoma.engine.event.ExpenseSharesUpdatedEvent;
import com.kartaguez.pocoma.engine.event.PotCreatedEvent;
import com.kartaguez.pocoma.engine.event.PotDeletedEvent;
import com.kartaguez.pocoma.engine.event.PotDetailsUpdatedEvent;
import com.kartaguez.pocoma.engine.event.PotShareholdersAddedEvent;
import com.kartaguez.pocoma.engine.event.PotShareholdersDetailsUpdatedEvent;
import com.kartaguez.pocoma.engine.event.PotShareholdersWeightsUpdatedEvent;
import com.kartaguez.pocoma.observability.event.EventMetadata;
import com.kartaguez.pocoma.observability.trace.TraceContextHolder;
import com.kartaguez.pocoma.supra.worker.projection.core.ProjectionTask;
import com.kartaguez.pocoma.supra.worker.projection.core.SegmentedProjectionWorker;

public class ProjectionEventListener {

	private final SegmentedProjectionWorker worker;

	ProjectionEventListener(SegmentedProjectionWorker worker) {
		this.worker = Objects.requireNonNull(worker, "worker must not be null");
	}

	@EventListener
	public void on(ExpenseCreatedEvent event) {
		submit(event.potId(), event.version(), event);
	}

	@EventListener
	public void on(ExpenseDeletedEvent event) {
		submit(event.potId(), event.version(), event);
	}

	@EventListener
	public void on(ExpenseDetailsUpdatedEvent event) {
		submit(event.potId(), event.version(), event);
	}

	@EventListener
	public void on(ExpenseSharesUpdatedEvent event) {
		submit(event.potId(), event.version(), event);
	}

	@EventListener
	public void on(PotCreatedEvent event) {
		submit(event.potId(), event.version(), event);
	}

	@EventListener
	public void on(PotDeletedEvent event) {
		submit(event.potId(), event.version(), event);
	}

	@EventListener
	public void on(PotDetailsUpdatedEvent event) {
		submit(event.potId(), event.version(), event);
	}

	@EventListener
	public void on(PotShareholdersAddedEvent event) {
		submit(event.potId(), event.version(), event);
	}

	@EventListener
	public void on(PotShareholdersDetailsUpdatedEvent event) {
		submit(event.potId(), event.version(), event);
	}

	@EventListener
	public void on(PotShareholdersWeightsUpdatedEvent event) {
		submit(event.potId(), event.version(), event);
	}

	private void submit(com.kartaguez.pocoma.domain.value.id.PotId potId, long version, Object event) {
		long submittedAtNanos = System.nanoTime();
		ProjectionTask task = TraceContextHolder.current()
				.map(context -> new ProjectionTask(
						potId,
						version,
						EventMetadata.type(event),
						context.traceId(),
						context.commandCommittedAtNanos(),
						submittedAtNanos))
				.orElseGet(() -> new ProjectionTask(
						potId,
						version,
						EventMetadata.type(event),
						null,
						null,
						submittedAtNanos));
		worker.submit(task);
	}
}
