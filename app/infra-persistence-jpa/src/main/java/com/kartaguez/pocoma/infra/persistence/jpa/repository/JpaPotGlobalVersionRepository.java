package com.kartaguez.pocoma.infra.persistence.jpa.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kartaguez.pocoma.infra.persistence.jpa.entity.JpaPotGlobalVersionEntity;

public interface JpaPotGlobalVersionRepository extends JpaRepository<JpaPotGlobalVersionEntity, UUID> {

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update JpaPotGlobalVersionEntity potGlobalVersion
			set potGlobalVersion.version = :nextVersion
			where potGlobalVersion.potId = :potId
				and potGlobalVersion.version = :expectedVersion
			""")
	int updateIfActive(
			@Param("potId") UUID potId,
			@Param("expectedVersion") long expectedVersion,
			@Param("nextVersion") long nextVersion);

	@Query("""
			select potGlobalVersion.potId as potId,
				potGlobalVersion.version as currentVersion,
				projectionState.projectedVersion as projectedVersion
			from JpaPotGlobalVersionEntity potGlobalVersion
			left join JpaPotBalanceProjectionStateEntity projectionState
				on projectionState.potId = potGlobalVersion.potId
			""")
	List<ProjectionVersionGapRow> findProjectionVersionGaps();
}
