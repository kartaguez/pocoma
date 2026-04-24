package com.kartaguez.pocoma.infra.persistence.jpa.adapter;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.association.ExpenseShare;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseSharesPort;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.JpaExpenseHeaderEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.JpaExpenseShareEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.JpaExpenseHeaderRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.JpaExpenseShareRepository;

@Component
public class JpaExpenseSharesAdapter implements ExpenseSharesPort {

	private final JpaExpenseShareRepository expenseShareRepository;
	private final JpaExpenseHeaderRepository expenseHeaderRepository;

	public JpaExpenseSharesAdapter(
			JpaExpenseShareRepository expenseShareRepository,
			JpaExpenseHeaderRepository expenseHeaderRepository) {
		this.expenseShareRepository = Objects.requireNonNull(
				expenseShareRepository,
				"expenseShareRepository must not be null");
		this.expenseHeaderRepository = Objects.requireNonNull(
				expenseHeaderRepository,
				"expenseHeaderRepository must not be null");
	}

	@Override
	@Transactional(readOnly = true)
	public ExpenseShares loadActiveAtVersion(ExpenseId expenseId, long version) {
		Objects.requireNonNull(expenseId, "expenseId must not be null");

		List<JpaExpenseShareEntity> entities = expenseShareRepository.findActiveAtVersion(expenseId.value(), version);
		PotId potId = entities.stream()
				.map(entity -> PotId.of(entity.potId()))
				.findFirst()
				.orElseGet(() -> loadPotIdFromHeader(expenseId, version));
		Set<ExpenseShare> shares = entities.stream()
				.map(JpaExpenseShareEntity::toDomain)
				.collect(Collectors.toSet());

		return ExpenseShares.reconstitute(potId, shares);
	}

	@Override
	@Transactional
	public void saveNew(ExpenseId expenseId, ExpenseShares expenseShares, long version) {
		Objects.requireNonNull(expenseId, "expenseId must not be null");
		Objects.requireNonNull(expenseShares, "expenseShares must not be null");
		saveEntities(expenseId, expenseShares, version);
	}

	@Override
	@Transactional
	public void save(
			ExpenseId expenseId,
			ExpenseShares expenseShares,
			PotGlobalVersion currentVersion,
			PotGlobalVersion nextVersion) {
		Objects.requireNonNull(expenseId, "expenseId must not be null");
		Objects.requireNonNull(expenseShares, "expenseShares must not be null");
		Objects.requireNonNull(currentVersion, "currentVersion must not be null");
		Objects.requireNonNull(nextVersion, "nextVersion must not be null");

		if (!currentVersion.potId().equals(nextVersion.potId())) {
			throw new IllegalArgumentException("nextVersion must reference the same pot as currentVersion");
		}
		if (!expenseShares.potId().equals(currentVersion.potId())) {
			throw new IllegalArgumentException("expenseShares must reference the same pot as currentVersion");
		}
		if (nextVersion.version() <= currentVersion.version()) {
			throw new IllegalArgumentException("nextVersion must be greater than currentVersion");
		}

		List<JpaExpenseShareEntity> activeEntities =
				expenseShareRepository.findActiveAtVersion(expenseId.value(), currentVersion.version());
		int updatedRows = expenseShareRepository.closeActiveVersions(
				expenseId.value(),
				currentVersion.version(),
				nextVersion.version());
		if (updatedRows != activeEntities.size()) {
			throw new VersionConflictException("Expense shares have been modified by another operation");
		}

		saveEntities(expenseId, expenseShares, nextVersion.version());
	}

	private PotId loadPotIdFromHeader(ExpenseId expenseId, long version) {
		return expenseHeaderRepository.findActiveAtVersion(expenseId.value(), version)
				.map(JpaExpenseHeaderEntity::toDomain)
				.map(header -> header.potId())
				.orElseThrow(() -> new BusinessEntityNotFoundException(
						"EXPENSE_SHARES",
						"Expense shares active at requested version were not found"));
	}

	private void saveEntities(ExpenseId expenseId, ExpenseShares expenseShares, long startedAtVersion) {
		List<JpaExpenseShareEntity> entities = expenseShares.shares().values().stream()
				.map(share -> toEntity(expenseId, expenseShares.potId(), share, startedAtVersion))
				.toList();
		expenseShareRepository.saveAll(entities);
	}

	private static JpaExpenseShareEntity toEntity(
			ExpenseId expenseId,
			PotId potId,
			ExpenseShare share,
			long startedAtVersion) {
		if (!share.expenseId().equals(expenseId)) {
			throw new IllegalArgumentException("expense share must reference the saved expense");
		}
		return JpaExpenseShareEntity.from(potId, share, startedAtVersion, null);
	}
}
