package com.kartaguez.pocoma.engine.service.taskcreation;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.engine.port.in.taskcreation.strategy.EventPipelineRelevance;

public final class EventPipelineRelevanceRegistry {
	private final Map<PipelineId, EventPipelineRelevance> relevances;

	public EventPipelineRelevanceRegistry(Collection<? extends EventPipelineRelevance> relevances) {
		requireNonNull(relevances, "relevances must not be null");
		this.relevances = relevances.stream()
				.map(value -> requireNonNull(value, "relevance must not be null"))
				.collect(Collectors.toUnmodifiableMap(EventPipelineRelevance::pipelineId, Function.identity(),
						(left, right) -> { throw new IllegalArgumentException(
								"Duplicate Event relevance for pipeline " + left.pipelineId().value()); }));
	}

	public Optional<EventPipelineRelevance> find(PipelineId pipelineId) {
		return Optional.ofNullable(relevances.get(requireNonNull(pipelineId, "pipelineId must not be null")));
	}
}
