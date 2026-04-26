package com.kartaguez.pocoma.infra.persistence.jpa.repository.projection;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kartaguez.pocoma.infra.persistence.jpa.entity.projection.JpaPotBalanceProjectionStateEntity;

public interface JpaPotBalanceProjectionStateRepository
		extends JpaRepository<JpaPotBalanceProjectionStateEntity, UUID> {

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update JpaPotBalanceProjectionStateEntity state
			set state.projectedVersion = :nextVersion
			where state.potId = :potId
				and state.projectedVersion = :expectedVersion
			""")
	int updateIfProjectedVersion(
			@Param("potId") UUID potId,
			@Param("expectedVersion") long expectedVersion,
			@Param("nextVersion") long nextVersion);
}
