package com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineTaskEntity;

/** Creation/adoption repository. It intentionally contains no Task lifecycle operation. */
public interface JpaPipelineTaskRepository extends JpaRepository<JpaPipelineTaskEntity, UUID> {
	List<JpaPipelineTaskEntity> findByMaterializationIdOrderById(UUID materializationId);
}
