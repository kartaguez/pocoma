package com.kartaguez.pocoma.infra.persistence.jpa.adapter.processing.task;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.out.processing.task.TaskPort;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;
import com.kartaguez.pocoma.engine.processing.segmentation.PartitionHash;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.processing.task.ordering.TaskSearchCursor;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline.JpaTaskReadRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline.JpaTaskReadRepository.TaskRow;

@Component
public class JpaTaskPort implements TaskPort {
	private static final int PAGE_SIZE = 128;
	private static final Instant BEGINNING = Instant.parse("1970-01-01T00:00:00Z");
	private static final UUID NIL = new UUID(0, 0);
	private final JpaTaskReadRepository tasks;

	public JpaTaskPort(JpaTaskReadRepository tasks) { this.tasks = tasks; }

	@Override
	@Transactional(readOnly = true)
	public Optional<RecordedTask> findNextCandidate(PipelineDefinition pipeline, WorkerSegment segment,
			Optional<TaskSearchCursor> afterExclusive) {
		TaskSearchCursor cursor = afterExclusive.orElse(new TaskSearchCursor(BEGINNING, NIL));
		while (true) {
			var page = tasks.findCandidates(pipeline.pipelineId().value(), pipeline.pipelineVersion(), cursor, PAGE_SIZE);
			if (page.isEmpty()) return Optional.empty();
			for (TaskRow row : page) {
				cursor = new TaskSearchCursor(row.createdAt(), row.taskId());
				PotId potId = potId(row);
				if (segment.owns(PartitionHash.forPipelinePot(pipeline.pipelineId().value(), potId.value()))) {
					return Optional.of(toDomain(row));
				}
			}
			if (page.size() < PAGE_SIZE) return Optional.empty();
		}
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
	public Optional<RecordedTask> findById(UUID taskId) {
		return tasks.findById(taskId).map(JpaTaskPort::toDomain);
	}

	private static RecordedTask toDomain(TaskRow row) {
		return new RecordedTask(row.taskId(),
				new PipelineDefinition(PipelineId.of(row.pipelineId()), row.pipelineVersion()),
				potId(row), row.targetVersion(), row.createdAt(), row.taskType(), row.taskPayload(), Optional.empty());
	}

	private static PotId potId(TaskRow row) {
		if (row.partitionKey() == null) throw new IllegalStateException("Task partitionKey must contain its Pot id");
		return PotId.of(UUID.fromString(row.partitionKey()));
	}
}
