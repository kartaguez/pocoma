package com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineMaterializationEntity;

public interface JpaPipelineMaterializationRepository
		extends JpaRepository<JpaPipelineMaterializationEntity, UUID> {

	Optional<JpaPipelineMaterializationEntity> findByEventIdAndPipelineIdAndPipelineVersion(
			UUID eventId,
			String pipelineId,
			int pipelineVersion);
}
