package com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionInput;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionResult;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionProvenancePersistencePort;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.consumption.JpaConsumptionInputEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.consumption.JpaConsumptionResultEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionInputRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionResultRepository;

public class JpaConsumptionProvenanceAdapter implements ConsumptionProvenancePersistencePort {

	private final JpaConsumptionInputRepository inputs;
	private final JpaConsumptionResultRepository results;

	public JpaConsumptionProvenanceAdapter(
			JpaConsumptionInputRepository inputs, JpaConsumptionResultRepository results) {
		this.inputs = requireNonNull(inputs, "inputs must not be null");
		this.results = requireNonNull(results, "results must not be null");
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void appendInputs(List<ConsumptionInput> values) {
		requireNonNull(values, "inputs must not be null");
		inputs.saveAll(values.stream().map(value -> new JpaConsumptionInputEntity(
				UUID.randomUUID(),
				value.slotId(),
				value.subjectType(),
				value.subjectId(),
				value.subjectVersion())).toList());
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void appendResults(List<ConsumptionResult> values) {
		requireNonNull(values, "results must not be null");
		results.saveAll(values.stream().map(value -> new JpaConsumptionResultEntity(
				UUID.randomUUID(),
				value.slotId(),
				value.space(),
				value.objectType(),
				value.objectId(),
				value.objectVersion().isPresent() ? value.objectVersion().getAsLong() : null,
				value.subjectType().orElse(null),
				value.subjectId().orElse(null),
				value.subjectVersion().isPresent() ? value.subjectVersion().getAsLong() : null,
				value.createdAt())).toList());
	}

	@Override
	@Transactional(readOnly = true)
	public List<ConsumptionInput> findInputs(UUID slotId) {
		return inputs.findBySlotIdOrderBySubjectTypeAscSubjectIdAscSubjectVersionAsc(
				requireNonNull(slotId, "slotId must not be null"))
				.stream()
				.map(entity -> new ConsumptionInput(
						entity.slotId(), entity.subjectType(), entity.subjectId(), entity.subjectVersion()))
				.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public List<ConsumptionResult> findResults(UUID slotId) {
		return results.findBySlotIdOrderByCreatedAtAscResultIdAsc(
				requireNonNull(slotId, "slotId must not be null"))
				.stream().map(this::toDomain).toList();
	}

	private ConsumptionResult toDomain(JpaConsumptionResultEntity entity) {
		return new ConsumptionResult(
				entity.slotId(),
				entity.space(),
				entity.objectType(),
				entity.objectId(),
				entity.objectVersion() == null ? OptionalLong.empty() : OptionalLong.of(entity.objectVersion()),
				Optional.ofNullable(entity.subjectType()),
				Optional.ofNullable(entity.subjectId()),
				entity.subjectVersion() == null ? OptionalLong.empty() : OptionalLong.of(entity.subjectVersion()),
				entity.createdAt());
	}
}
