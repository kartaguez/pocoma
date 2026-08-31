package com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineMaterializationEntity;

public interface JpaPipelineMaterializationRepository
		extends JpaRepository<JpaPipelineMaterializationEntity, UUID> {

	Optional<JpaPipelineMaterializationEntity> findByEventIdAndPipelineIdAndPipelineVersion(
			UUID eventId,
			String pipelineId,
			int pipelineVersion);

	@Modifying(flushAutomatically = true)
	@Query("""
			insert into JpaPipelineMaterializationEntity
				(id, eventId, pipelineId, pipelineVersion, status, attemptCount,
				 createdAt, updatedAt, materializedAt)
			values (:id, :eventId, :pipelineId, :pipelineVersion,
				com.kartaguez.pocoma.engine.taskmaterialization.model.PipelineMaterializationStatus.MATERIALIZED,
				0, :now, :now, :now)
			on conflict (eventId, pipelineId, pipelineVersion) do nothing
			""")
	int insertMaterializedIfAbsent(@Param("id") UUID id, @Param("eventId") UUID eventId,
			@Param("pipelineId") String pipelineId, @Param("pipelineVersion") int pipelineVersion,
			@Param("now") Instant now);
}
