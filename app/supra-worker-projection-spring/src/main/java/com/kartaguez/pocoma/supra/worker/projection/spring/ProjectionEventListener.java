package com.kartaguez.pocoma.supra.worker.projection.spring;

import java.util.Objects;

import org.springframework.context.event.EventListener;

import com.kartaguez.pocoma.domain.value.id.PotId;
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
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWorkerWakeBus;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWorkerWakeSignal;

public class ProjectionEventListener {

	private final ProjectionWorkerWakeBus wakeBus;

	ProjectionEventListener(ProjectionWorkerWakeBus wakeBus) {
		this.wakeBus = Objects.requireNonNull(wakeBus, "wakeBus must not be null");
	}

	@EventListener
	public void on(ExpenseCreatedEvent event) {
		wakeTaskBuilder(event.potId());
	}

	@EventListener
	public void on(ExpenseDeletedEvent event) {
		wakeTaskBuilder(event.potId());
	}

	@EventListener
	public void on(ExpenseDetailsUpdatedEvent event) {
		wakeTaskBuilder(event.potId());
	}

	@EventListener
	public void on(ExpenseSharesUpdatedEvent event) {
		wakeTaskBuilder(event.potId());
	}

	@EventListener
	public void on(PotCreatedEvent event) {
		wakeTaskBuilder(event.potId());
	}

	@EventListener
	public void on(PotDeletedEvent event) {
		wakeTaskBuilder(event.potId());
	}

	@EventListener
	public void on(PotDetailsUpdatedEvent event) {
		wakeTaskBuilder(event.potId());
	}

	@EventListener
	public void on(PotShareholdersAddedEvent event) {
		wakeTaskBuilder(event.potId());
	}

	@EventListener
	public void on(PotShareholdersDetailsUpdatedEvent event) {
		wakeTaskBuilder(event.potId());
	}

	@EventListener
	public void on(PotShareholdersWeightsUpdatedEvent event) {
		wakeTaskBuilder(event.potId());
	}

	private void wakeTaskBuilder(PotId potId) {
		wakeBus.publish(ProjectionWorkerWakeSignal.BUSINESS_EVENTS_AVAILABLE, potId);
	}
}
