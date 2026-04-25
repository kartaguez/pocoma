package com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.projection.ProjectedExpense;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.engine.port.out.persistence.ProjectedExpensePort;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.core.JpaExpenseHeaderEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.core.JpaExpenseShareEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.core.JpaExpenseHeaderRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.core.JpaExpenseShareRepository;

@Component
public class JpaProjectedExpenseAdapter implements ProjectedExpensePort {

	private final JpaExpenseHeaderRepository expenseHeaderRepository;
	private final JpaExpenseShareRepository expenseShareRepository;

	public JpaProjectedExpenseAdapter(
			JpaExpenseHeaderRepository expenseHeaderRepository,
			JpaExpenseShareRepository expenseShareRepository) {
		this.expenseHeaderRepository = Objects.requireNonNull(
				expenseHeaderRepository,
				"expenseHeaderRepository must not be null");
		this.expenseShareRepository = Objects.requireNonNull(
				expenseShareRepository,
				"expenseShareRepository must not be null");
	}

	@Override
	@Transactional(readOnly = true)
	public Collection<ProjectedExpense> loadActiveAtSourceOnly(
			PotId potId,
			long sourceVersion,
			long comparedVersion) {
		Objects.requireNonNull(potId, "potId must not be null");

		return expenseHeaderRepository.findExpenseIdsActiveAtSourceOnly(
				potId.value(),
				sourceVersion,
				comparedVersion).stream()
				.map(expenseId -> loadProjectedExpense(expenseId, sourceVersion))
				.toList();
	}

	private ProjectedExpense loadProjectedExpense(UUID expenseId, long version) {
		ExpenseHeader header = expenseHeaderRepository.findActiveAtVersion(expenseId, version)
				.map(JpaExpenseHeaderEntity::toDomain)
				.orElseThrow(() -> new BusinessEntityNotFoundException(
						"PROJECTED_EXPENSE",
						"Projected expense header was not found"));
		ExpenseShares shares = ExpenseShares.reconstitute(
				header.potId(),
				expenseShareRepository.findActiveAtVersion(expenseId, version).stream()
						.map(JpaExpenseShareEntity::toDomain)
						.collect(java.util.stream.Collectors.toSet()));

		return new ProjectedExpense(header, shares);
	}
}
