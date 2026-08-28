package com.kartaguez.pocoma.engine.service.query;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.domain.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.policy.ReadPotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.snapshot.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.intent.ListPotExpensesQuery;
import com.kartaguez.pocoma.engine.port.in.query.usecase.ListPotExpensesUseCase;
import com.kartaguez.pocoma.engine.port.out.query.ExpenseQueryPort;
import com.kartaguez.pocoma.engine.port.out.query.PotQueryPort;
import com.kartaguez.pocoma.engine.security.UserContext;

final class ListPotExpensesService implements ListPotExpensesUseCase {

	private final PotQueryPort potQueryPort;
	private final ExpenseQueryPort expenseQueryPort;
	private final ReadPotAuthorizationPolicy readPotAuthorizationPolicy;

	ListPotExpensesService(
			PotQueryPort potQueryPort,
			ExpenseQueryPort expenseQueryPort,
			ReadPotAuthorizationPolicy readPotAuthorizationPolicy) {
		this.potQueryPort = Objects.requireNonNull(potQueryPort, "potQueryPort must not be null");
		this.expenseQueryPort = Objects.requireNonNull(expenseQueryPort, "expenseQueryPort must not be null");
		this.readPotAuthorizationPolicy = Objects.requireNonNull(
				readPotAuthorizationPolicy,
				"readPotAuthorizationPolicy must not be null");
	}

	@Override
	public List<ExpenseHeaderSnapshot> listPotExpenses(UserContext userContext, ListPotExpensesQuery query) {
		// 1. Validate the incoming query and convert simple input data into domain identifiers.
		Objects.requireNonNull(userContext, "userContext must not be null");
		Objects.requireNonNull(query, "query must not be null");
		PotId potId = PotId.of(query.potId());

		// 2. Resolve the version to read. Missing version means the current pot version.
		long version = query.version().orElseGet(() -> potQueryPort.currentVersion(potId).version());

		// 3. Load the pot header first because it carries the creator used by the read policy.
		PotHeader potHeader = potQueryPort.loadPotHeaderAtVersion(potId, version);
		PotShareholders potShareholders = potQueryPort.loadPotShareholdersAtVersion(potId, version);

		// 4. Check that the current user is allowed to read this pot at the requested version.
		readPotAuthorizationPolicy.assertCanReadPot(
				userContext.userId(),
				userContext.scopes(),
				potHeader.creatorId(),
				activeShareholderUserIds(potShareholders));

		// 5. Load non-deleted expense headers for this pot and version.
		return expenseQueryPort.listExpenseHeadersByPotAtVersion(potId, version).stream()

				// 6. Return versioned expense header snapshots to the caller.
				.map(header -> QuerySnapshotMapper.toSnapshot(header.expenseHeader(), header.version()))
				.toList();
	}

	private static Set<UserId> activeShareholderUserIds(PotShareholders shareholders) {
		return shareholders.shareholders().values().stream()
				.filter(shareholder -> !shareholder.deleted())
				.map(shareholder -> shareholder.userId())
				.collect(java.util.stream.Collectors.toSet());
	}
}
