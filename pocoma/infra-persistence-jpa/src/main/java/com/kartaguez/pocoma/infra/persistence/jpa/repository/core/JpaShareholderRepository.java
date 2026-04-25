package com.kartaguez.pocoma.infra.persistence.jpa.repository.core;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kartaguez.pocoma.infra.persistence.jpa.entity.core.JpaShareholderEntity;

public interface JpaShareholderRepository extends JpaRepository<JpaShareholderEntity, UUID> {

	@Query("""
			select shareholder
			from JpaShareholderEntity shareholder
			where shareholder.potId = :potId
				and shareholder.startedAtVersion <= :version
				and (shareholder.endedAtVersion is null or :version < shareholder.endedAtVersion)
			""")
	List<JpaShareholderEntity> findActiveAtVersion(
			@Param("potId") UUID potId,
			@Param("version") long version);

	@Query("""
			select shareholder
			from JpaShareholderEntity shareholder
			where shareholder.potId = :potId
				and shareholder.startedAtVersion <= :version
				and (shareholder.endedAtVersion is null or :version < shareholder.endedAtVersion)
				and shareholder.deleted = false
			""")
	List<JpaShareholderEntity> findActiveNotDeletedAtVersion(
			@Param("potId") UUID potId,
			@Param("version") long version);

	@Query("""
			select shareholder
			from JpaShareholderEntity shareholder
			where shareholder.potId = :potId
				and shareholder.userId = :userId
				and shareholder.deleted = false
				and shareholder.startedAtVersion <= :version
				and (shareholder.endedAtVersion is null or :version < shareholder.endedAtVersion)
			""")
	Optional<JpaShareholderEntity> findLinkedUserAtVersion(
			@Param("potId") UUID potId,
			@Param("userId") UUID userId,
			@Param("version") long version);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update JpaShareholderEntity shareholder
			set shareholder.endedAtVersion = :nextVersion
			where shareholder.potId = :potId
				and shareholder.shareholderId in :shareholderIds
				and shareholder.startedAtVersion <= :currentVersion
				and shareholder.endedAtVersion is null
			""")
	int closeActiveVersions(
			@Param("potId") UUID potId,
			@Param("shareholderIds") Set<UUID> shareholderIds,
			@Param("currentVersion") long currentVersion,
			@Param("nextVersion") long nextVersion);
}
