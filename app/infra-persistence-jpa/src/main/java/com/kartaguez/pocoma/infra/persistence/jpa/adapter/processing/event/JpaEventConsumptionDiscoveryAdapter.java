package com.kartaguez.pocoma.infra.persistence.jpa.adapter.processing.event;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventConsumptionCandidate;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventConsumptionDiscoveryPort;
import com.kartaguez.pocoma.engine.processing.event.ordering.EventOrderingKey;
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
	public Optional<EventConsumptionCandidate> findNextEligibleCandidate(PipelineDefinition pipeline,
			WorkerSegment segment, Instant now, Optional<EventOrderingKey> afterExclusive) {
		Optional<EventOrderingKey> cursor = afterExclusive;
		while (true) {
			var page = repository.findNextEligible(pipeline, now, cursor, PAGE_SIZE);
			if (page.isEmpty()) return Optional.empty();
			for (var row : page) {
				var candidate = new EventConsumptionCandidate(
						row.eventId(), PotId.of(row.potId()), row.version(), row.createdAt());
				cursor = Optional.of(candidate.orderingKey());
				if (segment.owns(PartitionHash.forPipelinePot(
						pipeline.pipelineId().value(), candidate.potId().value()))) {
					return Optional.of(candidate);
				}
			}
			if (page.size() < PAGE_SIZE) return Optional.empty();
		}
	}
}
