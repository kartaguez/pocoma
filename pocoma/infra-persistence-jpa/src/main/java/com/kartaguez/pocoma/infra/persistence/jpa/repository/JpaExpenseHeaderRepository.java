package com.kartaguez.pocoma.infra.persistence.jpa.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kartaguez.pocoma.infra.persistence.jpa.entity.JpaExpenseHeaderEntity;

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
