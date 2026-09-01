package com.kartaguez.pocoma.infra.persistence.jpa.adapter.processing.event;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventConsumptionDiscoveryPort;
import com.kartaguez.pocoma.engine.processing.event.ordering.EventOrderingKey;
import com.kartaguez.pocoma.engine.processing.segmentation.PartitionHash;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox.BusinessEventRecordMapper;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaEventConsumptionDiscoveryRepository;

@Component
public class JpaEventConsumptionDiscoveryAdapter implements EventConsumptionDiscoveryPort {
	private final JpaEventConsumptionDiscoveryRepository repository;
	private final BusinessEventRecordMapper mapper = new BusinessEventRecordMapper(new ObjectMapper());

	public JpaEventConsumptionDiscoveryAdapter(JpaEventConsumptionDiscoveryRepository repository) {
		this.repository = repository;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<RecordedEvent<? extends BusinessEvent>> findNextEligibleCandidate(PipelineDefinition pipeline,
			WorkerSegment segment, Instant now, Optional<EventOrderingKey> afterExclusive) {
		Optional<EventOrderingKey> cursor = afterExclusive;
		while (true) {
			var page = repository.findNextEligible(pipeline, now, cursor);
			if (page.isEmpty()) return Optional.empty();
			for (var entity : page) {
				var envelope = entity.toEnvelope();
				cursor = Optional.of(new EventOrderingKey(envelope.version(), envelope.createdAt(), envelope.id()));
				if (segment.owns(PartitionHash.forPipelinePot(
						pipeline.pipelineId().value(), envelope.potId().value()))) {
					return Optional.of(mapper.toRecordedEvent(envelope));
				}
			}
			if (page.size() < 128) return Optional.empty();
		}
	}
}
