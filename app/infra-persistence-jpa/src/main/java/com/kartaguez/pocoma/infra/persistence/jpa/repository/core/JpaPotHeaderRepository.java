package com.kartaguez.pocoma.infra.persistence.jpa.repository.core;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kartaguez.pocoma.infra.persistence.jpa.entity.core.JpaPotHeaderEntity;

public interface JpaPotHeaderRepository extends JpaRepository<JpaPotHeaderEntity, UUID> {

	@Query("""
			select potHeader
			from JpaPotHeaderEntity potHeader
			where potHeader.potId = :potId
				and potHeader.startedAtVersion <= :version
				and (potHeader.endedAtVersion is null or :version < potHeader.endedAtVersion)
			""")
	Optional<JpaPotHeaderEntity> findActiveAtVersion(
			@Param("potId") UUID potId,
			@Param("version") long version);

	@Query("""
			select potHeader
			from JpaPotHeaderEntity potHeader
			join JpaPotGlobalVersionEntity potGlobalVersion on potGlobalVersion.potId = potHeader.potId
			where potHeader.startedAtVersion <= potGlobalVersion.version
				and (potHeader.endedAtVersion is null or potGlobalVersion.version < potHeader.endedAtVersion)
				and potHeader.deleted = false
				and (
					potHeader.creatorId = :userId
					or exists (
						select 1
						from JpaShareholderEntity shareholder
						where shareholder.potId = potHeader.potId
							and shareholder.userId = :userId
							and shareholder.deleted = false
							and shareholder.startedAtVersion <= potGlobalVersion.version
							and (
								shareholder.endedAtVersion is null
								or potGlobalVersion.version < shareholder.endedAtVersion
							)
					)
				)
			order by potHeader.label asc, potHeader.potId asc
			""")
	List<JpaPotHeaderEntity> findCurrentAccessibleNotDeletedByUserId(@Param("userId") UUID userId);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update JpaPotHeaderEntity potHeader
			set potHeader.endedAtVersion = :nextVersion
			where potHeader.potId = :potId
				and potHeader.startedAtVersion <= :currentVersion
				and potHeader.endedAtVersion is null
			""")
	int closeActiveVersion(
			@Param("potId") UUID potId,
			@Param("currentVersion") long currentVersion,
			@Param("nextVersion") long nextVersion);
}
