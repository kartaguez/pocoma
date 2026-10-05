package com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.consumption;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kartaguez.pocoma.infra.persistence.primary.jpa.entity.consumption.JpaConsumptionInputEntity;

public interface JpaConsumptionInputRepository extends JpaRepository<JpaConsumptionInputEntity, UUID> {

	List<JpaConsumptionInputEntity> findBySlotIdOrderBySubjectTypeAscSubjectIdAscSubjectVersionAsc(UUID slotId);
}
