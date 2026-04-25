package com.kartaguez.pocoma.supra.worker.projection.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.projection.PotBalances;
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
import com.kartaguez.pocoma.supra.worker.projection.core.ProjectionWorkerSettings;
import com.kartaguez.pocoma.supra.worker.projection.core.SegmentedProjectionWorker;

class ProjectionEventListenerTest {

	@Test
	void submitsEveryProjectionEventType() throws InterruptedException {
		CountDownLatch submitted = new CountDownLatch(10);
		List<Long> versions = Collections.synchronizedList(new ArrayList<>());
		ProjectionWorkerSettings settings = new ProjectionWorkerSettings(
				1,
				Integer.MAX_VALUE,
				ProjectionWorkerSettings.DEFAULT_MAX_RETRIES,
				Duration.ZERO,
				Duration.ZERO);
		SegmentedProjectionWorker worker = new SegmentedProjectionWorker((potId, targetVersion) -> {
			versions.add(targetVersion);
			submitted.countDown();
			return new PotBalances(potId, targetVersion, Map.of());
		}, settings);
		ProjectionEventListener listener = new ProjectionEventListener(worker);
		PotId potId = PotId.of(UUID.randomUUID());
		ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		Set<ShareholderId> shareholderIds = Set.of(ShareholderId.of(UUID.randomUUID()));

		worker.start();
		try {
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

			assertTrue(submitted.await(2, TimeUnit.SECONDS));
			assertEquals(List.of(2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L), versions);
		}
		finally {
			worker.stop();
		}
	}
}
