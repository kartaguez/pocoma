package com.kartaguez.pocoma.infra.persistence.jpa.adapter.pipeline;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.taskmaterialization.model.ConfiguredPipelineBinding;
import com.kartaguez.pocoma.engine.taskmaterialization.model.EventPipelineMaterializationCandidate;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.outbox.JpaBusinessEventOutboxEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineMaterializationEntity;
import com.kartaguez.pocoma.supra.worker.taskmaterialization.core.MaterializableEventSource;

import jakarta.persistence.EntityManager;

@Component
public class JpaMaterializableEventSourceAdapter implements MaterializableEventSource {

	private final EntityManager entityManager;

	public JpaMaterializableEventSourceAdapter(EntityManager entityManager) {
		this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
	}

	@Override
	@Transactional(readOnly = true)
	public List<EventPipelineMaterializationCandidate> findUnmaterializedEventPipelinePairs(
			int limit,
			ProjectionPartition partition,
			Instant upperBound,
			List<ConfiguredPipelineBinding> activeBindings) {
		requirePositive(limit, "limit");
		Objects.requireNonNull(partition, "partition must not be null");
		Objects.requireNonNull(upperBound, "upperBound must not be null");
		List<ConfiguredPipelineBinding> bindings = enabledBindings(activeBindings);
		List<EventPipelineMaterializationCandidate> candidates = new ArrayList<>();
		for (ConfiguredPipelineBinding binding : bindings) {
			int remaining = limit - candidates.size();
			if (remaining < 1) {
				break;
			}
			for (JpaBusinessEventOutboxEntity event : findCandidates(binding, partition, upperBound, remaining)) {
				candidates.add(new EventPipelineMaterializationCandidate(event.toEnvelope(), binding.definition()));
			}
		}
		return List.copyOf(candidates);
	}

	@Override
	@Transactional(readOnly = true)
	public long countUnmaterialized(List<ConfiguredPipelineBinding> activeBindings) {
		return enabledBindings(activeBindings).stream()
				.mapToLong(this::countCandidates)
				.sum();
	}

	private List<JpaBusinessEventOutboxEntity> findCandidates(
			ConfiguredPipelineBinding binding,
			ProjectionPartition partition,
			Instant upperBound,
			int limit) {
		String eventTypePredicate = matchesEveryEventType(binding) ? "" : "and event.eventType in :eventTypes";
		var query = entityManager.createQuery("""
				select event
				from JpaBusinessEventOutboxEntity event
				where event.createdAt <= :upperBound
					and mod(event.potPartitionHash, :segmentCount) = :segmentIndex
					%s
					and not exists (
						select materialization.id
						from JpaPipelineMaterializationEntity materialization
						where materialization.eventId = event.id
							and materialization.pipelineId = :pipelineId
							and materialization.pipelineVersion = :pipelineVersion
					)
				order by event.createdAt, event.id
				""".formatted(eventTypePredicate), JpaBusinessEventOutboxEntity.class)
				.setParameter("upperBound", upperBound)
				.setParameter("segmentCount", partition.segmentCount())
				.setParameter("segmentIndex", partition.segmentIndex())
				.setParameter("pipelineId", binding.definition().pipelineId().value())
				.setParameter("pipelineVersion", binding.definition().pipelineVersion())
				.setMaxResults(limit);
		if (!matchesEveryEventType(binding)) {
			query.setParameter("eventTypes", eventTypes(binding));
		}
		return query.getResultList();
	}

	private long countCandidates(ConfiguredPipelineBinding binding) {
		String predicate = matchesEveryEventType(binding)
				? "where not exists"
				: "where event.eventType in :eventTypes and not exists";
		var query = entityManager.createQuery("""
				select count(event)
				from JpaBusinessEventOutboxEntity event
				%s (
						select materialization.id
						from JpaPipelineMaterializationEntity materialization
						where materialization.eventId = event.id
							and materialization.pipelineId = :pipelineId
							and materialization.pipelineVersion = :pipelineVersion
					)
				""".formatted(predicate), Long.class)
				.setParameter("pipelineId", binding.definition().pipelineId().value())
				.setParameter("pipelineVersion", binding.definition().pipelineVersion());
		if (!matchesEveryEventType(binding)) {
			query.setParameter("eventTypes", eventTypes(binding));
		}
		return query.getSingleResult();
	}

	private static List<ConfiguredPipelineBinding> enabledBindings(List<ConfiguredPipelineBinding> bindings) {
		Objects.requireNonNull(bindings, "bindings must not be null");
		return bindings.stream()
				.filter(ConfiguredPipelineBinding::enabled)
				.toList();
	}

	private static List<String> eventTypes(ConfiguredPipelineBinding binding) {
		return binding.eventTypes().stream()
				.filter(eventType -> !"*".equals(eventType))
				.toList();
	}

	private static boolean matchesEveryEventType(ConfiguredPipelineBinding binding) {
		return binding.eventTypes().contains("*");
	}

	private static void requirePositive(int value, String name) {
		if (value < 1) {
			throw new IllegalArgumentException(name + " must be greater than or equal to 1");
		}
	}
}
