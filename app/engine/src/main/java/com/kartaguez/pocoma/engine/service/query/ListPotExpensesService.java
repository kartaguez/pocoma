package com.kartaguez.pocoma.engine.service.query;

import java.util.List;
import java.util.Objects;

import com.kartaguez.pocoma.domain.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.policy.ReadPotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.command.result.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.intent.ListPotExpensesQuery;
import com.kartaguez.pocoma.engine.port.in.query.usecase.ListPotExpensesUseCase;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseQueryPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotQueryPort;
import com.kartaguez.pocoma.engine.security.UserContext;

final class ListPotExpensesService implements ListPotExpensesUseCase {

	private final PotQueryPort potQueryPort;
	private final ExpenseQueryPort expenseQueryPort;
	private final QueryAuthorizationService authorizationService;

	ListPotExpensesService(
			PotQueryPort potQueryPort,
			ExpenseQueryPort expenseQueryPort,
			ReadPotAuthorizationPolicy readPotAuthorizationPolicy) {
		this.potQueryPort = Objects.requireNonNull(potQueryPort, "potQueryPort must not be null");
		this.expenseQueryPort = Objects.requireNonNull(expenseQueryPort, "expenseQueryPort must not be null");
		this.authorizationService = new QueryAuthorizationService(potQueryPort, readPotAuthorizationPolicy);
	}

	@Override
	public List<ExpenseHeaderSnapshot> listPotExpenses(UserContext userContext, ListPotExpensesQuery query) {
		// 1. Validate the incoming query and convert simple input data into domain identifiers.
		Objects.requireNonNull(query, "query must not be null");
		PotId potId = PotId.of(query.potId());

		// 2. Resolve the version to read. Missing version means the current pot version.
		long version = query.version().orElseGet(() -> potQueryPort.currentVersion(potId).version());

		// 3. Load the pot header first because it carries the creator used by the read policy.
		PotHeader potHeader = potQueryPort.loadPotHeaderAtVersion(potId, version);

		// 4. Check that the current user is allowed to read this pot at the requested version.
		authorizationService.assertCanRead(userContext, potHeader, potId, version);

		// 5. Load non-deleted expense headers for this pot and version.
		return expenseQueryPort.listExpenseHeadersByPotAtVersion(potId, version).stream()

				// 6. Return versioned expense header snapshots to the caller.
				.map(header -> QuerySnapshotMapper.toSnapshot(header.expenseHeader(), header.version()))
				.toList();
	}
}
