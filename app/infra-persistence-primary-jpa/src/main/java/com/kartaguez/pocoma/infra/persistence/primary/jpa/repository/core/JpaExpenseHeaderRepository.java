package com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.core;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kartaguez.pocoma.infra.persistence.primary.jpa.entity.core.JpaExpenseHeaderEntity;

public interface JpaExpenseHeaderRepository extends JpaRepository<JpaExpenseHeaderEntity, UUID> {

	@Query("""
			select expenseHeader
			from JpaExpenseHeaderEntity expenseHeader
			where expenseHeader.expenseId = :expenseId
				and expenseHeader.startedAtVersion <= :version
				and (expenseHeader.endedAtVersion is null or :version < expenseHeader.endedAtVersion)
			""")
	Optional<JpaExpenseHeaderEntity> findActiveAtVersion(
			@Param("expenseId") UUID expenseId,
			@Param("version") long version);

	@Query("""
			select expenseHeader
			from JpaExpenseHeaderEntity expenseHeader
			where expenseHeader.expenseId = :expenseId
				and expenseHeader.endedAtVersion is null
			""")
	Optional<JpaExpenseHeaderEntity> findCurrentActive(
			@Param("expenseId") UUID expenseId);

	@Query("""
			select expenseHeader
			from JpaExpenseHeaderEntity expenseHeader
			where expenseHeader.potId = :potId
				and expenseHeader.deleted = false
				and expenseHeader.startedAtVersion <= :version
				and (expenseHeader.endedAtVersion is null or :version < expenseHeader.endedAtVersion)
			order by expenseHeader.startedAtVersion asc, expenseHeader.expenseId asc
			""")
	List<JpaExpenseHeaderEntity> findByPotActiveNotDeletedAtVersion(
			@Param("potId") UUID potId,
			@Param("version") long version);

	@Query("""
			select expenseHeader
			from JpaExpenseHeaderEntity expenseHeader
			where expenseHeader.potId = :potId
				and expenseHeader.startedAtVersion <= :version
				and (expenseHeader.endedAtVersion is null or :version < expenseHeader.endedAtVersion)
			order by expenseHeader.expenseId asc
			""")
	List<JpaExpenseHeaderEntity> findByPotActiveAtVersion(
			@Param("potId") UUID potId,
			@Param("version") long version);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update JpaExpenseHeaderEntity expenseHeader
			set expenseHeader.endedAtVersion = :nextVersion
			where expenseHeader.expenseId = :expenseId
				and expenseHeader.startedAtVersion <= :currentVersion
				and expenseHeader.endedAtVersion is null
			""")
	int closeActiveVersion(
			@Param("expenseId") UUID expenseId,
			@Param("currentVersion") long currentVersion,
			@Param("nextVersion") long nextVersion);
}
