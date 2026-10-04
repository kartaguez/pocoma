package com.kartaguez.pocoma.engine.produce.projectiontask.materialization;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.engine.produce.projectiontask.port.ProjectionMaterializationCandidate;
import com.kartaguez.pocoma.port.projection.task.ProjectionTaskStorePort;

/** Production policy for one exact task derived from an Event candidate. */
public final class ProduceProjectionTaskService {
    private final ProjectionTaskStorePort tasks;

    public ProduceProjectionTaskService(ProjectionTaskStorePort tasks) {
        this.tasks = requireNonNull(tasks, "tasks must not be null");
    }

    public void ensure(ProjectionMaterializationCandidate candidate) {
        requireNonNull(candidate, "candidate must not be null");
        var key = new ProjectionKey(candidate.projectionType(), candidate.targetObjectType(),
                candidate.targetObjectId(), candidate.targetVersion());
        tasks.ensure(key, candidate.recordedAt());
    }
}
