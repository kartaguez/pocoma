package com.kartaguez.pocoma.infra.persistence.jpa.adapter;

import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.engine.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseHeaderPort;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.JpaExpenseHeaderEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.JpaExpenseHeaderRepository;

@Component
public class JpaExpenseHeaderAdapter implements ExpenseHeaderPort {

	private final JpaExpenseHeaderRepository repository;

	public JpaExpenseHeaderAdapter(JpaExpenseHeaderRepository repository) {
		this.repository = Objects.requireNonNull(repository, "repository must not be null");
	}

	@Override
	@Transactional(readOnly = true)
	public ExpenseHeader loadActiveAtVersion(ExpenseId expenseId, long version) {
		Objects.requireNonNull(expenseId, "expenseId must not be null");
		return repository.findActiveAtVersion(expenseId.value(), version)
				.map(JpaExpenseHeaderEntity::toDomain)
				.orElseThrow(() -> new BusinessEntityNotFoundException(
						"EXPENSE_HEADER",
						"Expense header active at requested version was not found"));
	}

	@Override
	@Transactional
	public void saveNew(ExpenseHeader expenseHeader, long version) {
		Objects.requireNonNull(expenseHeader, "expenseHeader must not be null");
		repository.save(JpaExpenseHeaderEntity.from(expenseHeader, version, null));
	}

	@Override
	@Transactional
	public void save(ExpenseHeader expenseHeader, PotGlobalVersion currentVersion, PotGlobalVersion nextVersion) {
		Objects.requireNonNull(expenseHeader, "expenseHeader must not be null");
		Objects.requireNonNull(currentVersion, "currentVersion must not be null");
		Objects.requireNonNull(nextVersion, "nextVersion must not be null");

		if (!currentVersion.potId().equals(nextVersion.potId())) {
			throw new IllegalArgumentException("nextVersion must reference the same pot as currentVersion");
		}
		if (!expenseHeader.potId().equals(currentVersion.potId())) {
			throw new IllegalArgumentException("expenseHeader must reference the same pot as currentVersion");
		}
		if (nextVersion.version() <= currentVersion.version()) {
			throw new IllegalArgumentException("nextVersion must be greater than currentVersion");
		}

		int updatedRows = repository.closeActiveVersion(
				expenseHeader.id().value(),
				currentVersion.version(),
				nextVersion.version());

		if (updatedRows != 1) {
			throw new VersionConflictException("Expense header has been modified by another operation");
		}

		repository.save(JpaExpenseHeaderEntity.from(expenseHeader, nextVersion.version(), null));
	}
}
