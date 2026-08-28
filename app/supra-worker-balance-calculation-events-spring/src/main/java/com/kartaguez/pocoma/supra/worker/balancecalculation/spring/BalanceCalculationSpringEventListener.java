package com.kartaguez.pocoma.supra.worker.balancecalculation.spring;

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
import com.kartaguez.pocoma.supra.dispatcher.projection.shared.model.ProjectionTask;
import com.kartaguez.pocoma.supra.worker.balancecalculation.core.SegmentedBalanceCalculationWorker;

public class BalanceCalculationSpringEventListener {

	private final SegmentedBalanceCalculationWorker worker;

	BalanceCalculationSpringEventListener(SegmentedBalanceCalculationWorker worker) {
		this.worker = Objects.requireNonNull(worker, "worker must not be null");
	}

	@EventListener
	public void on(ExpenseCreatedEvent event) {
		submit(event.potId(), event.version(), event.getClass().getSimpleName());
	}

	@EventListener
	public void on(ExpenseDeletedEvent event) {
		submit(event.potId(), event.version(), event.getClass().getSimpleName());
	}

	@EventListener
	public void on(ExpenseDetailsUpdatedEvent event) {
		submit(event.potId(), event.version(), event.getClass().getSimpleName());
	}

	@EventListener
	public void on(ExpenseSharesUpdatedEvent event) {
		submit(event.potId(), event.version(), event.getClass().getSimpleName());
	}

	@EventListener
	public void on(PotCreatedEvent event) {
		submit(event.potId(), event.version(), event.getClass().getSimpleName());
	}

	@EventListener
	public void on(PotDeletedEvent event) {
		submit(event.potId(), event.version(), event.getClass().getSimpleName());
	}

	@EventListener
	public void on(PotDetailsUpdatedEvent event) {
		submit(event.potId(), event.version(), event.getClass().getSimpleName());
	}

	@EventListener
	public void on(PotShareholdersAddedEvent event) {
		submit(event.potId(), event.version(), event.getClass().getSimpleName());
	}

	@EventListener
	public void on(PotShareholdersDetailsUpdatedEvent event) {
		submit(event.potId(), event.version(), event.getClass().getSimpleName());
	}

	@EventListener
	public void on(PotShareholdersWeightsUpdatedEvent event) {
		submit(event.potId(), event.version(), event.getClass().getSimpleName());
	}

	private void submit(PotId potId, long targetVersion, String sourceEventType) {
		worker.submit(new ProjectionTask(potId, targetVersion, sourceEventType));
	}
}
