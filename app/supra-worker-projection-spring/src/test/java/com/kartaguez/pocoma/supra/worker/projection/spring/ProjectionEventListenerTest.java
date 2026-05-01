package com.kartaguez.pocoma.supra.worker.projection.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
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
import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWorkerWakeBus;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWorkerWakeEvent;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWorkerWakeSignal;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWorkerWakeSubscription;

class ProjectionEventListenerTest {

	@Test
	void wakesTaskBuilderForEveryProjectionEventType() {
		RecordingWakeBus wakeBus = new RecordingWakeBus();
		ProjectionEventListener listener = new ProjectionEventListener(wakeBus);
		PotId potId = PotId.of(UUID.randomUUID());
		ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		Set<ShareholderId> shareholderIds = Set.of(ShareholderId.of(UUID.randomUUID()));

		listener.on(new ExpenseCreatedEvent(expenseId, potId, 2));
		listener.on(new ExpenseDeletedEvent(expenseId, potId, 3));
		listener.on(new ExpenseDetailsUpdatedEvent(expenseId, potId, 4));
		listener.on(new ExpenseSharesUpdatedEvent(expenseId, potId, 5));
		listener.on(new PotCreatedEvent(potId, 6));
		listener.on(new PotDeletedEvent(potId, 7));
		listener.on(new PotDetailsUpdatedEvent(potId, 8));
		listener.on(new PotShareholdersAddedEvent(potId, shareholderIds, 9));
		listener.on(new PotShareholdersDetailsUpdatedEvent(potId, shareholderIds, 10));
		listener.on(new PotShareholdersWeightsUpdatedEvent(potId, shareholderIds, 11));

		assertEquals(10, wakeBus.signals.size());
		assertEquals(List.of(potId, potId, potId, potId, potId, potId, potId, potId, potId, potId), wakeBus.potIds);
		assertEquals(
				List.of(
						ProjectionWorkerWakeSignal.BUSINESS_EVENTS_AVAILABLE,
						ProjectionWorkerWakeSignal.BUSINESS_EVENTS_AVAILABLE,
						ProjectionWorkerWakeSignal.BUSINESS_EVENTS_AVAILABLE,
						ProjectionWorkerWakeSignal.BUSINESS_EVENTS_AVAILABLE,
						ProjectionWorkerWakeSignal.BUSINESS_EVENTS_AVAILABLE,
						ProjectionWorkerWakeSignal.BUSINESS_EVENTS_AVAILABLE,
						ProjectionWorkerWakeSignal.BUSINESS_EVENTS_AVAILABLE,
						ProjectionWorkerWakeSignal.BUSINESS_EVENTS_AVAILABLE,
						ProjectionWorkerWakeSignal.BUSINESS_EVENTS_AVAILABLE,
						ProjectionWorkerWakeSignal.BUSINESS_EVENTS_AVAILABLE),
				wakeBus.signals);
	}

	private static final class RecordingWakeBus implements ProjectionWorkerWakeBus {
		private final List<ProjectionWorkerWakeSignal> signals = new ArrayList<>();
		private final List<PotId> potIds = new ArrayList<>();

		@Override
		public void publish(ProjectionWorkerWakeEvent event) {
			signals.add(event.signal());
			potIds.add(event.potId());
		}

		@Override
		public ProjectionWorkerWakeSubscription subscribe(
				Set<ProjectionWorkerWakeSignal> signals,
				ProjectionPartition partition,
				Runnable listener) {
			return () -> {
			};
		}
	}
}
