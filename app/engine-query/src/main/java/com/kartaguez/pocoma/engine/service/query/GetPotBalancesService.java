package com.kartaguez.pocoma.engine.service.query;

import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.domain.pot.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.pot.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.pot.policy.ReadBalanceAuthorizationPolicy;
import com.kartaguez.pocoma.domain.projection.balance.PotBalances;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.query.intent.GetPotBalancesQuery;
import com.kartaguez.pocoma.engine.port.in.query.result.PotBalancesSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.usecase.GetPotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.out.query.PotBalancesQueryPort;
import com.kartaguez.pocoma.engine.port.out.query.PotQueryPort;
import com.kartaguez.pocoma.engine.security.UserContext;

final class GetPotBalancesService implements GetPotBalancesUseCase {

	private final PotQueryPort potQueryPort;
	private final PotBalancesQueryPort potBalancesPort;
	private final ReadBalanceAuthorizationPolicy readBalanceAuthorizationPolicy;

	GetPotBalancesService(
			PotQueryPort potQueryPort,
			PotBalancesQueryPort potBalancesPort,
			ReadBalanceAuthorizationPolicy readBalanceAuthorizationPolicy) {
		this.potQueryPort = Objects.requireNonNull(potQueryPort, "potQueryPort must not be null");
		this.potBalancesPort = Objects.requireNonNull(potBalancesPort, "potBalancesPort must not be null");
		this.readBalanceAuthorizationPolicy = Objects.requireNonNull(
				readBalanceAuthorizationPolicy,
				"readBalanceAuthorizationPolicy must not be null");
	}

	@Override
	public PotBalancesSnapshot getPotBalances(UserContext userContext, GetPotBalancesQuery query) {
		// 1. Validate the incoming query and convert simple input data into domain identifiers.
		Objects.requireNonNull(userContext, "userContext must not be null");
		Objects.requireNonNull(query, "query must not be null");
		PotId potId = PotId.of(query.potId());

		// 2. Resolve the version to read. Missing version means the current pot version.
		long version = query.version().orElseGet(() -> potQueryPort.currentVersion(potId).version());

		// 3. Load the pot header first because it carries the creator used by the read policy.
		PotHeader potHeader = potQueryPort.loadPotHeaderAtVersion(potId, version);
		PotShareholders potShareholders = potQueryPort.loadPotShareholdersAtVersion(potId, version);

		// 4. Check that the current user is allowed to read balances for this pot at the requested version.
		readBalanceAuthorizationPolicy.assertCanReadBalance(
				userContext.userId(),
				userContext.permissions(),
				potHeader.creatorId(),
				activeShareholderUserIds(potShareholders));

		// 5. Load the balances projection for the requested version.
		PotBalances potBalances = potBalancesPort.loadAtVersion(potId, version);

		// 6. Return a versioned snapshot to the caller.
		return QuerySnapshotMapper.toSnapshot(potBalances);
	}

	private static Set<UserId> activeShareholderUserIds(PotShareholders shareholders) {
		return shareholders.shareholders().values().stream()
				.filter(shareholder -> !shareholder.deleted())
				.map(shareholder -> shareholder.userId())
				.collect(java.util.stream.Collectors.toSet());
	}
}
