package com.kartaguez.pocoma.supra.worker.balancecalculation.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.event.PotCreatedEvent;
import com.kartaguez.pocoma.supra.dispatcher.projection.shared.model.ProjectionTask;
import com.kartaguez.pocoma.supra.worker.balancecalculation.core.SegmentedBalanceCalculationWorker;

class BalanceCalculationSpringEventListenerTest {

	@Test
	void submitsProjectionTaskFromSpringEvent() {
		SegmentedBalanceCalculationWorker worker = mock(SegmentedBalanceCalculationWorker.class);
		BalanceCalculationSpringEventListener listener = new BalanceCalculationSpringEventListener(worker);
		PotId potId = PotId.of(UUID.randomUUID());

		listener.on(new PotCreatedEvent(potId, 7));

		ArgumentCaptor<ProjectionTask> captor = ArgumentCaptor.forClass(ProjectionTask.class);
		verify(worker).submit(captor.capture());
		ProjectionTask task = captor.getValue();
		assertEquals(potId, task.potId());
		assertEquals(7, task.targetVersion());
		assertEquals("PotCreatedEvent", task.sourceEventType());
	}
}
