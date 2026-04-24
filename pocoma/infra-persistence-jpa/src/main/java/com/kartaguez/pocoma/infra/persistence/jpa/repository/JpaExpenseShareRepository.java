package com.kartaguez.pocoma.infra.persistence.jpa.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kartaguez.pocoma.infra.persistence.jpa.entity.JpaExpenseShareEntity;

public interface JpaExpenseShareRepository extends JpaRepository<JpaExpenseShareEntity, UUID> {

	@Query("""
			select expenseShare
			from JpaExpenseShareEntity expenseShare
			where expenseShare.expenseId = :expenseId
				and expenseShare.startedAtVersion <= :version
				and (expenseShare.endedAtVersion is null or :version < expenseShare.endedAtVersion)
			""")
	List<JpaExpenseShareEntity> findActiveAtVersion(
			@Param("expenseId") UUID expenseId,
			@Param("version") long version);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update JpaExpenseShareEntity expenseShare
			set expenseShare.endedAtVersion = :nextVersion
			where expenseShare.expenseId = :expenseId
				and expenseShare.startedAtVersion <= :currentVersion
				and expenseShare.endedAtVersion is null
			""")
	int closeActiveVersions(
			@Param("expenseId") UUID expenseId,
			@Param("currentVersion") long currentVersion,
			@Param("nextVersion") long nextVersion);
}
