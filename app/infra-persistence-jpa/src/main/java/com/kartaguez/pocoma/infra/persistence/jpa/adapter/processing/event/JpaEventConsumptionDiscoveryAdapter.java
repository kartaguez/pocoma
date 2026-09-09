package com.kartaguez.pocoma.infra.persistence.jpa.adapter.processing.event;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineVersionDefinition;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventSchedulingCandidate;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventConsumptionDiscoveryPort;
import com.kartaguez.pocoma.engine.processing.event.ordering.EventSchedulingOrderingKey;
import com.kartaguez.pocoma.engine.processing.segmentation.PartitionHash;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaEventConsumptionDiscoveryRepository;

@Component
public class JpaEventConsumptionDiscoveryAdapter implements EventConsumptionDiscoveryPort {
	private static final int PAGE_SIZE = 128;
	private final JpaEventConsumptionDiscoveryRepository repository;

	public JpaEventConsumptionDiscoveryAdapter(JpaEventConsumptionDiscoveryRepository repository) {
		this.repository = repository;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<EventSchedulingCandidate> findNextEligibleCandidate(
			Collection<PipelineVersionDefinition> definitions, WorkerSegment segment, Instant now,
			Optional<EventSchedulingOrderingKey> afterExclusive) {
		Optional<EventSchedulingOrderingKey> cursor = afterExclusive;
		while (true) {
			var page = repository.findNextEligible(definitions, now, cursor, PAGE_SIZE);
			if (page.isEmpty()) return Optional.empty();
			for (var row : page) {
				var trigger = new PipelineDefinition(row.pipelineId(), row.pipelineVersion());
				var candidate = new EventSchedulingCandidate(
						row.eventId(), PotId.of(row.potId()), row.version(), row.createdAt(), trigger);
				cursor = Optional.of(candidate.orderingKey());
				if (segment.owns(PartitionHash.forPipelinePot(
						trigger.pipelineId().value(), candidate.potId().value()))) {
					return Optional.of(candidate);
				}
			}
			if (page.size() < PAGE_SIZE) return Optional.empty();
		}
	}
}
