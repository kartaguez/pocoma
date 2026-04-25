package com.kartaguez.pocoma.engine.service.query;

import java.util.Objects;

import com.kartaguez.pocoma.domain.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.policy.ReadPotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.engine.port.in.query.intent.GetExpenseQuery;
import com.kartaguez.pocoma.engine.port.in.query.result.ExpenseViewSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.usecase.GetExpenseUseCase;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseQueryPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotQueryPort;
import com.kartaguez.pocoma.engine.security.UserContext;

final class GetExpenseService implements GetExpenseUseCase {

	private final PotQueryPort potQueryPort;
	private final ExpenseQueryPort expenseQueryPort;
	private final QueryAuthorizationService authorizationService;

	GetExpenseService(
			PotQueryPort potQueryPort,
			ExpenseQueryPort expenseQueryPort,
			ReadPotAuthorizationPolicy readPotAuthorizationPolicy) {
		this.potQueryPort = Objects.requireNonNull(potQueryPort, "potQueryPort must not be null");
		this.expenseQueryPort = Objects.requireNonNull(expenseQueryPort, "expenseQueryPort must not be null");
		this.authorizationService = new QueryAuthorizationService(potQueryPort, readPotAuthorizationPolicy);
	}

	@Override
	public ExpenseViewSnapshot getExpense(UserContext userContext, GetExpenseQuery query) {
		// 1. Validate the incoming query and convert simple input data into domain identifiers.
		Objects.requireNonNull(query, "query must not be null");
		ExpenseId expenseId = ExpenseId.of(query.expenseId());

		// 2. Load enough expense data to resolve the pot and the version to read.
		ExpenseHeader currentOrVersionedHeader = query.version().isPresent()
				? expenseQueryPort.loadExpenseHeaderAtVersion(expenseId, query.version().getAsLong())
				: expenseQueryPort.loadCurrentExpenseHeader(expenseId);
		long version = query.version().orElseGet(() ->
				potQueryPort.currentVersion(currentOrVersionedHeader.potId()).version());

		// 3. Reload the expense header at the resolved pot version when the caller did not specify one.
		ExpenseHeader expenseHeader = query.version().isPresent()
				? currentOrVersionedHeader
				: expenseQueryPort.loadExpenseHeaderAtVersion(expenseId, version);

		// 4. Load the pot header and check that the current user can read this pot at the requested version.
		PotHeader potHeader = potQueryPort.loadPotHeaderAtVersion(expenseHeader.potId(), version);
		authorizationService.assertCanRead(userContext, potHeader, expenseHeader.potId(), version);

		// 5. Load the expense shares once authorization has succeeded.
		ExpenseShares expenseShares = expenseQueryPort.loadExpenseSharesAtVersion(expenseId, version);

		// 6. Return a versioned snapshot to the caller.
		return new ExpenseViewSnapshot(
				QuerySnapshotMapper.toSnapshot(expenseHeader, version),
				QuerySnapshotMapper.toSnapshot(expenseId, expenseShares, version));
	}
}
