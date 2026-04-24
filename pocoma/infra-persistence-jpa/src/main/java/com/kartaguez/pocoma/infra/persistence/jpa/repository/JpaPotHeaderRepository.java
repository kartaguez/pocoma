package com.kartaguez.pocoma.infra.persistence.jpa.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kartaguez.pocoma.infra.persistence.jpa.entity.JpaPotHeaderEntity;

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
