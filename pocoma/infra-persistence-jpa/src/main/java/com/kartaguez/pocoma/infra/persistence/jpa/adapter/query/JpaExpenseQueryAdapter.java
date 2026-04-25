package com.kartaguez.pocoma.infra.persistence.jpa.adapter.query;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseQueryPort;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.core.JpaExpenseHeaderEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.core.JpaExpenseShareEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.core.JpaExpenseHeaderRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.core.JpaExpenseShareRepository;

@Component
public class JpaExpenseQueryAdapter implements ExpenseQueryPort {

	private final JpaExpenseHeaderRepository expenseHeaderRepository;
	private final JpaExpenseShareRepository expenseShareRepository;

	public JpaExpenseQueryAdapter(
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
	public ExpenseHeader loadCurrentExpenseHeader(ExpenseId expenseId) {
		Objects.requireNonNull(expenseId, "expenseId must not be null");
		return expenseHeaderRepository.findCurrentActive(expenseId.value())
				.map(JpaExpenseHeaderEntity::toDomain)
				.orElseThrow(() -> new BusinessEntityNotFoundException(
						"EXPENSE_HEADER",
						"Current active expense header was not found"));
	}

	@Override
	@Transactional(readOnly = true)
	public ExpenseHeader loadExpenseHeaderAtVersion(ExpenseId expenseId, long version) {
		Objects.requireNonNull(expenseId, "expenseId must not be null");
		return expenseHeaderRepository.findActiveAtVersion(expenseId.value(), version)
				.map(JpaExpenseHeaderEntity::toDomain)
				.orElseThrow(() -> new BusinessEntityNotFoundException(
						"EXPENSE_HEADER",
						"Expense header active at requested version was not found"));
	}

	@Override
	@Transactional(readOnly = true)
	public ExpenseShares loadExpenseSharesAtVersion(ExpenseId expenseId, long version) {
		Objects.requireNonNull(expenseId, "expenseId must not be null");
		ExpenseHeader header = loadExpenseHeaderAtVersion(expenseId, version);
		return ExpenseShares.reconstitute(
				header.potId(),
				expenseShareRepository.findActiveAtVersion(expenseId.value(), version).stream()
						.map(JpaExpenseShareEntity::toDomain)
						.collect(Collectors.toSet()));
	}

	@Override
	@Transactional(readOnly = true)
	public List<VersionedExpenseHeader> listExpenseHeadersByPotAtVersion(PotId potId, long version) {
		Objects.requireNonNull(potId, "potId must not be null");
		return expenseHeaderRepository.findByPotActiveNotDeletedAtVersion(potId.value(), version).stream()
				.map(entity -> new VersionedExpenseHeader(entity.toDomain(), version))
				.toList();
	}
}
