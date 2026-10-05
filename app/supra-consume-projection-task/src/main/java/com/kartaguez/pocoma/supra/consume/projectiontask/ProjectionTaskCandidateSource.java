package com.kartaguez.pocoma.supra.consume.projectiontask;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.port.projection.task.ProjectionTaskCandidate;
import com.kartaguez.pocoma.port.projection.task.ProjectionTaskStorePort;
import com.kartaguez.pocoma.orchestrator.consumption.fenced.FencedConsumptionCandidateSearch;
import com.kartaguez.pocoma.orchestrator.consumption.fenced.FencedConsumptionCandidateSource;

/** Candidate lookup for the Task consumer; the store adapter retains SQL ownership. */
public final class ProjectionTaskCandidateSource implements FencedConsumptionCandidateSource<ProjectionTaskCandidate> {
    private final Set<ProjectionType> types;
    private final int segmentIndex;
    private final int segmentCount;
    private final ProjectionTaskStorePort tasks;

    public ProjectionTaskCandidateSource(Set<ProjectionType> projectionTypes, int segmentIndex, int segmentCount,
            ProjectionTaskStorePort tasks) {
        this.types = Set.copyOf(requireNonNull(projectionTypes));
        if (types.isEmpty()) throw new IllegalArgumentException("projectionTypes must not be empty");
        if (segmentCount < 1 || segmentIndex < 0 || segmentIndex >= segmentCount)
            throw new IllegalArgumentException("invalid segment");
        this.segmentIndex = segmentIndex;
        this.segmentCount = segmentCount;
        this.tasks = requireNonNull(tasks);
    }

    @Override
    public FencedConsumptionCandidateSearch<ProjectionTaskCandidate> openSearch() {
        return new FencedConsumptionCandidateSearch<>() {
            private Optional<Instant> afterTime = Optional.empty();
            private Optional<UUID> afterId = Optional.empty();

            @Override
            public List<ProjectionTaskCandidate> nextPage(int limit) {
                return tasks.findCandidates(types, segmentIndex, segmentCount, afterTime, afterId, limit);
            }

            @Override
            public void candidateInspected(ProjectionTaskCandidate candidate) {
                afterTime = Optional.of(candidate.createdAt());
                afterId = Optional.of(candidate.rowId());
            }
        };
    }
}
