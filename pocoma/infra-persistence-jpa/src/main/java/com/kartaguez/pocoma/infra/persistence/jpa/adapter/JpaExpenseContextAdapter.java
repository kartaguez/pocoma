package com.kartaguez.pocoma.infra.persistence.jpa.adapter;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.context.DeleteExpenseContext;
import com.kartaguez.pocoma.engine.context.UpdateExpenseDetailsContext;
import com.kartaguez.pocoma.engine.context.UpdateExpenseSharesContext;
import com.kartaguez.pocoma.engine.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseContextPort;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.JpaPotHeaderEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.JpaExpenseHeaderRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.JpaPotGlobalVersionRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.JpaPotHeaderRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.JpaShareholderRepository;

@Component
public class JpaExpenseContextAdapter implements ExpenseContextPort {

	private final JpaExpenseHeaderRepository expenseHeaderRepository;
	private final JpaPotGlobalVersionRepository potGlobalVersionRepository;
	private final JpaPotHeaderRepository potHeaderRepository;
	private final JpaShareholderRepository shareholderRepository;

	public JpaExpenseContextAdapter(
			JpaExpenseHeaderRepository expenseHeaderRepository,
			JpaPotGlobalVersionRepository potGlobalVersionRepository,
			JpaPotHeaderRepository potHeaderRepository,
			JpaShareholderRepository shareholderRepository) {
		this.expenseHeaderRepository = Objects.requireNonNull(
				expenseHeaderRepository,
				"expenseHeaderRepository must not be null");
		this.potGlobalVersionRepository = Objects.requireNonNull(
				potGlobalVersionRepository,
				"potGlobalVersionRepository must not be null");
		this.potHeaderRepository = Objects.requireNonNull(potHeaderRepository, "potHeaderRepository must not be null");
		this.shareholderRepository = Objects.requireNonNull(
				shareholderRepository,
				"shareholderRepository must not be null");
	}

	@Override
	@Transactional(readOnly = true)
	public DeleteExpenseContext loadDeleteExpenseContext(ExpenseId expenseId) {
		ExpenseContextData contextData = loadExpenseContextData(expenseId);
		return new DeleteExpenseContext(
				contextData.potGlobalVersion(),
				contextData.expenseHeader().deleted(),
				UserId.of(contextData.potHeader().creatorId()));
	}

	@Override
	@Transactional(readOnly = true)
	public UpdateExpenseDetailsContext loadUpdateExpenseDetailsContext(ExpenseId expenseId) {
		ExpenseContextData contextData = loadExpenseContextData(expenseId);
		return new UpdateExpenseDetailsContext(
				contextData.potGlobalVersion(),
				contextData.expenseHeader().deleted(),
				UserId.of(contextData.potHeader().creatorId()),
				loadShareholderIds(contextData.expenseHeader().potId(), contextData.potGlobalVersion().version()));
	}

	@Override
	@Transactional(readOnly = true)
	public UpdateExpenseSharesContext loadUpdateExpenseSharesContext(ExpenseId expenseId) {
		ExpenseContextData contextData = loadExpenseContextData(expenseId);
		return new UpdateExpenseSharesContext(
				contextData.potGlobalVersion(),
				contextData.expenseHeader().deleted(),
				UserId.of(contextData.potHeader().creatorId()),
				loadShareholderIds(contextData.expenseHeader().potId(), contextData.potGlobalVersion().version()));
	}

	private ExpenseContextData loadExpenseContextData(ExpenseId expenseId) {
		Objects.requireNonNull(expenseId, "expenseId must not be null");
		ExpenseHeader expenseHeader = expenseHeaderRepository.findCurrentActive(expenseId.value())
				.map(entity -> entity.toDomain())
				.orElseThrow(() -> new BusinessEntityNotFoundException(
						"EXPENSE_HEADER",
						"Current active expense header was not found"));
		PotGlobalVersion potGlobalVersion = potGlobalVersionRepository.findById(expenseHeader.potId().value())
				.map(entity -> entity.toDomain())
				.orElseThrow(() -> new BusinessEntityNotFoundException(
						"POT_GLOBAL_VERSION",
						"Pot global version was not found"));
		JpaPotHeaderEntity potHeader = potHeaderRepository
				.findActiveAtVersion(expenseHeader.potId().value(), potGlobalVersion.version())
				.orElseThrow(() -> new BusinessEntityNotFoundException(
						"POT_HEADER",
						"Pot header active at current version was not found"));
		return new ExpenseContextData(potGlobalVersion, expenseHeader, potHeader);
	}

	private Set<ShareholderId> loadShareholderIds(PotId potId, long version) {
		return shareholderRepository.findActiveNotDeletedAtVersion(potId.value(), version).stream()
				.map(shareholder -> ShareholderId.of(shareholder.shareholderId()))
				.collect(Collectors.toUnmodifiableSet());
	}

	private record ExpenseContextData(
			PotGlobalVersion potGlobalVersion,
			ExpenseHeader expenseHeader,
			JpaPotHeaderEntity potHeader) {
	}
}
