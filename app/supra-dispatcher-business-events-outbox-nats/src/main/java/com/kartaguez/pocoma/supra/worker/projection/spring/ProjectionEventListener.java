package com.kartaguez.pocoma.supra.worker.projection.spring;

import java.util.Objects;

import org.springframework.context.event.EventListener;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.event.ExpenseCreatedEvent;
import com.kartaguez.pocoma.domain.pot.event.ExpenseDeletedEvent;
import com.kartaguez.pocoma.domain.pot.event.ExpenseDetailsUpdatedEvent;
import com.kartaguez.pocoma.domain.pot.event.ExpenseSharesUpdatedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotDeletedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotDetailsUpdatedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotShareholdersAddedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotShareholdersDetailsUpdatedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotShareholdersWeightsUpdatedEvent;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;
import com.kartaguez.pocoma.supra.dispatcher.projection.shared.wakeup.ProjectionWakeSignals;

public class ProjectionEventListener {

	private final WorkWakeBus<String, PotId> wakeBus;

	ProjectionEventListener(WorkWakeBus<String, PotId> wakeBus) {
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
		wakeBus.publish(ProjectionWakeSignals.BUSINESS_EVENTS_AVAILABLE, potId);
	}
}
