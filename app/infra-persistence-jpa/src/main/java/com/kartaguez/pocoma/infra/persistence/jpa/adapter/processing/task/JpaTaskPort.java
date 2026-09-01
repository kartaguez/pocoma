package com.kartaguez.pocoma.infra.persistence.jpa.adapter.processing.task;

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
import com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline.JpaTaskReadRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline.JpaTaskReadRepository.TaskRow;

@Component
public class JpaTaskPort implements TaskPort {
	private final JpaTaskReadRepository tasks;

	public JpaTaskPort(JpaTaskReadRepository tasks) { this.tasks = tasks; }

	@Override
	@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
	public Optional<RecordedTask> findById(UUID taskId) {
		return tasks.findById(taskId).map(JpaTaskPort::toDomain);
	}

	static RecordedTask toDomain(TaskRow row) {
		return new RecordedTask(row.taskId(),
				new PipelineDefinition(PipelineId.of(row.pipelineId()), row.pipelineVersion()),
				potId(row), row.targetVersion(), row.createdAt(), row.taskType(), row.taskPayload(), Optional.empty());
	}

	private static PotId potId(TaskRow row) {
		if (row.partitionKey() == null) throw new IllegalStateException("Task partitionKey must contain its Pot id");
		return PotId.of(UUID.fromString(row.partitionKey()));
	}
}
