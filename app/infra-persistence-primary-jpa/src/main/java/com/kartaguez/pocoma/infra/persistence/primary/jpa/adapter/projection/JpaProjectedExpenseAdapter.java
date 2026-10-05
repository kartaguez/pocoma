package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.projection;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.pot.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.pot.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.projection.balance.ProjectedExpense;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.write.pot.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.entity.core.JpaExpenseHeaderEntity;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.entity.core.JpaExpenseShareEntity;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.core.JpaExpenseHeaderRepository;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.core.JpaExpenseShareRepository;

@Component
public class JpaProjectedExpenseAdapter {

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

	@Transactional(readOnly = true)
	public Collection<ProjectedExpense> loadActiveAtVersion(PotId potId, long version) {
		Objects.requireNonNull(potId, "potId must not be null");

		return expenseHeaderRepository.findByPotActiveNotDeletedAtVersion(potId.value(), version).stream()
				.map(header -> loadProjectedExpense(header.expenseId(), version))
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
