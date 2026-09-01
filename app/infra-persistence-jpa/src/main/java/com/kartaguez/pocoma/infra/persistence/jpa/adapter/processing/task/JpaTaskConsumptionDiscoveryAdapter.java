package com.kartaguez.pocoma.infra.persistence.jpa.adapter.processing.task;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.out.processing.task.TaskConsumptionDiscoveryPort;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;
import com.kartaguez.pocoma.engine.processing.segmentation.PartitionHash;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.processing.task.ordering.TaskSearchCursor;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaTaskConsumptionDiscoveryRepository;

@Component
public class JpaTaskConsumptionDiscoveryAdapter implements TaskConsumptionDiscoveryPort {
	private static final int PAGE_SIZE = 128;
	private static final TaskSearchCursor BEGINNING =
			new TaskSearchCursor(Instant.parse("1970-01-01T00:00:00Z"), new UUID(0L, 0L));
	private final JpaTaskConsumptionDiscoveryRepository repository;

	public JpaTaskConsumptionDiscoveryAdapter(JpaTaskConsumptionDiscoveryRepository repository) {
		this.repository = repository;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<RecordedTask> findNextEligibleCandidate(PipelineDefinition pipeline, WorkerSegment segment,
			Instant now, Optional<TaskSearchCursor> afterExclusive) {
		TaskSearchCursor cursor = afterExclusive.orElse(BEGINNING);
		while (true) {
			var page = repository.findNextEligible(pipeline.pipelineId().value(), pipeline.pipelineVersion(),
					now, cursor, PAGE_SIZE);
			if (page.isEmpty()) return Optional.empty();
			for (var row : page) {
				cursor = new TaskSearchCursor(row.createdAt(), row.taskId());
				PotId potId = PotId.of(UUID.fromString(row.partitionKey()));
				if (segment.owns(PartitionHash.forPipelinePot(pipeline.pipelineId().value(), potId.value()))) {
					return Optional.of(JpaTaskPort.toDomain(row));
				}
			}
			if (page.size() < PAGE_SIZE) return Optional.empty();
		}
	}
}
