package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.context;

import java.util.Objects;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.infra.persistence.primary.jpa.entity.core.JpaExpenseHeaderEntity;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.core.JpaExpenseShareRepository;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.write.pot.context.DeleteExpenseContext;
import com.kartaguez.pocoma.engine.write.pot.context.UpdateExpenseDetailsContext;
import com.kartaguez.pocoma.engine.write.pot.context.UpdateExpenseSharesContext;
import com.kartaguez.pocoma.engine.write.pot.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.domain.pot.version.PotGlobalVersion;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.ExpenseContextPort;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.entity.core.JpaPotHeaderEntity;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.core.JpaExpenseHeaderRepository;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.JpaPotGlobalVersionRepository;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.core.JpaPotHeaderRepository;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.core.JpaShareholderRepository;

@Component
public class JpaExpenseContextAdapter implements ExpenseContextPort {

	private final JpaExpenseHeaderRepository expenseHeaderRepository;
	private final JpaExpenseShareRepository expenseShareRepository;
	private final JpaPotGlobalVersionRepository potGlobalVersionRepository;
	private final JpaPotHeaderRepository potHeaderRepository;
	private final JpaShareholderRepository shareholderRepository;

	public JpaExpenseContextAdapter(
			JpaExpenseHeaderRepository expenseHeaderRepository,
			JpaPotGlobalVersionRepository potGlobalVersionRepository,
			JpaPotHeaderRepository potHeaderRepository,
			JpaShareholderRepository shareholderRepository, JpaExpenseShareRepository expenseShareRepository) {
		this.expenseShareRepository = Objects.requireNonNull(expenseShareRepository);
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
		ShareholderRelations shareholderRelations = loadShareholderRelations(
				contextData.potGlobalVersion().potId(), contextData.potGlobalVersion().version());
		return new DeleteExpenseContext(
				contextData.potGlobalVersion(),
				contextData.expenseHeader().deleted(),
				contextData.potHeader().deleted(),
				UserId.of(contextData.potHeader().creatorId()),
				shareholderRelations.shareholderUsers(),
				contextData.expenseHeader().startedAtVersion(), loadShareVersions(expenseId, contextData.potGlobalVersion()));
	}

	@Override
	@Transactional(readOnly = true)
	public UpdateExpenseDetailsContext loadUpdateExpenseDetailsContext(ExpenseId expenseId) {
		ExpenseContextData contextData = loadExpenseContextData(expenseId);
		ShareholderRelations shareholderRelations = loadShareholderRelations(
				contextData.potGlobalVersion().potId(), contextData.potGlobalVersion().version());
		return new UpdateExpenseDetailsContext(
				contextData.potGlobalVersion(),
				contextData.expenseHeader().deleted(),
				contextData.potHeader().deleted(),
				UserId.of(contextData.potHeader().creatorId()),
				shareholderRelations.shareholderIds(),
				shareholderRelations.shareholderUsers(),
				contextData.expenseHeader().startedAtVersion());
	}

	@Override
	@Transactional(readOnly = true)
	public UpdateExpenseSharesContext loadUpdateExpenseSharesContext(ExpenseId expenseId) {
		ExpenseContextData contextData = loadExpenseContextData(expenseId);
		ShareholderRelations shareholderRelations = loadShareholderRelations(
				contextData.potGlobalVersion().potId(), contextData.potGlobalVersion().version());
		return new UpdateExpenseSharesContext(
				contextData.potGlobalVersion(),
				contextData.expenseHeader().deleted(),
				contextData.potHeader().deleted(),
				UserId.of(contextData.potHeader().creatorId()),
				shareholderRelations.shareholderIds(),
				shareholderRelations.shareholderUsers(),
				loadShareVersions(expenseId, contextData.potGlobalVersion()));
	}

	private List<Long> loadShareVersions(ExpenseId expenseId, PotGlobalVersion version) {
		var shares = expenseShareRepository.findActiveAtVersion(expenseId.value(), version.version());
		var ids = new HashSet<java.util.UUID>();
		for (var share : shares) {
			if (!version.potId().value().equals(share.potId()) || !ids.add(share.shareholderId())) {
				throw new IllegalStateException("Inconsistent current expense shares ownership or duplicate shareholder");
			}
		}
		return shares.stream().map(share -> share.startedAtVersion()).toList();
	}

	private ExpenseContextData loadExpenseContextData(ExpenseId expenseId) {
		Objects.requireNonNull(expenseId, "expenseId must not be null");
		// This first read identifies ownership only; its lifecycle may precede the version read.
		PotId potId = expenseHeaderRepository.findCurrentActive(expenseId.value())
				.map(entity -> PotId.of(entity.potId()))
				.orElseThrow(() -> new BusinessEntityNotFoundException(
						"EXPENSE_HEADER",
						"Current active expense header was not found"));
		PotGlobalVersion potGlobalVersion = potGlobalVersionRepository.findById(potId.value())
				.map(entity -> entity.toDomain())
				.orElseThrow(() -> new BusinessEntityNotFoundException(
						"POT_GLOBAL_VERSION",
						"Pot global version was not found"));
		// All business facts must describe the same logical version, even under READ COMMITTED.
		// Later writers only close intervals at versions > V and append new rows, preserving V.
		JpaExpenseHeaderEntity expenseHeader = expenseHeaderRepository
				.findActiveAtVersion(expenseId.value(), potGlobalVersion.version())
				.orElseThrow(() -> new BusinessEntityNotFoundException(
						"EXPENSE_HEADER",
						"Expense header active at current pot version was not found"));
		if (!potId.value().equals(expenseHeader.potId())) {
			throw new IllegalStateException("Expense ownership must be stable across versions");
		}
		JpaPotHeaderEntity potHeader = potHeaderRepository
				.findActiveAtVersion(expenseHeader.potId(), potGlobalVersion.version())
				.orElseThrow(() -> new BusinessEntityNotFoundException(
						"POT_HEADER",
						"Pot header active at current version was not found"));
		return new ExpenseContextData(potGlobalVersion, expenseHeader, potHeader);
	}

	private ShareholderRelations loadShareholderRelations(PotId potId, long version) {
		var shareholders = shareholderRepository.findActiveNotDeletedAtVersion(potId.value(), version);
		Set<ShareholderId> shareholderIds = shareholders.stream()
				.map(shareholder -> ShareholderId.of(shareholder.shareholderId()))
				.collect(Collectors.toUnmodifiableSet());
		Map<ShareholderId, UserId> shareholderUsers = shareholders.stream()
				.filter(shareholder -> shareholder.userId() != null)
				.collect(Collectors.toUnmodifiableMap(
						shareholder -> ShareholderId.of(shareholder.shareholderId()),
						shareholder -> UserId.of(shareholder.userId())));
		return new ShareholderRelations(shareholderIds, shareholderUsers);
	}

	private record ExpenseContextData(
			PotGlobalVersion potGlobalVersion,
			JpaExpenseHeaderEntity expenseHeader,
			JpaPotHeaderEntity potHeader) {
	}

	private record ShareholderRelations(
			Set<ShareholderId> shareholderIds,
			Map<ShareholderId, UserId> shareholderUsers) {
	}
}
