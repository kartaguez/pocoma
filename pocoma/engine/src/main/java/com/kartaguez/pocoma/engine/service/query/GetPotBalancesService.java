package com.kartaguez.pocoma.engine.service.query;

import java.util.Objects;

import com.kartaguez.pocoma.domain.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.policy.ReadPotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.projection.PotBalances;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.query.intent.GetPotBalancesQuery;
import com.kartaguez.pocoma.engine.port.in.query.result.PotBalancesSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.usecase.GetPotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.out.persistence.PotBalancesPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotQueryPort;
import com.kartaguez.pocoma.engine.security.UserContext;

final class GetPotBalancesService implements GetPotBalancesUseCase {

	private final PotQueryPort potQueryPort;
	private final PotBalancesPort potBalancesPort;
	private final QueryAuthorizationService authorizationService;

	GetPotBalancesService(
			PotQueryPort potQueryPort,
			PotBalancesPort potBalancesPort,
			ReadPotAuthorizationPolicy readPotAuthorizationPolicy) {
		this.potQueryPort = Objects.requireNonNull(potQueryPort, "potQueryPort must not be null");
		this.potBalancesPort = Objects.requireNonNull(potBalancesPort, "potBalancesPort must not be null");
		this.authorizationService = new QueryAuthorizationService(potQueryPort, readPotAuthorizationPolicy);
	}

	@Override
	public PotBalancesSnapshot getPotBalances(UserContext userContext, GetPotBalancesQuery query) {
		// 1. Validate the incoming query and convert simple input data into domain identifiers.
		Objects.requireNonNull(query, "query must not be null");
		PotId potId = PotId.of(query.potId());

		// 2. Resolve the version to read. Missing version means the current pot version.
		long version = query.version().orElseGet(() -> potQueryPort.currentVersion(potId).version());

		// 3. Load the pot header first because it carries the creator used by the read policy.
		PotHeader potHeader = potQueryPort.loadPotHeaderAtVersion(potId, version);

		// 4. Check that the current user is allowed to read balances for this pot at the requested version.
		authorizationService.assertCanRead(userContext, potHeader, potId, version);

		// 5. Load the balances projection for the requested version.
		PotBalances potBalances = potBalancesPort.loadAtVersion(potId, version);

		// 6. Return a versioned snapshot to the caller.
		return QuerySnapshotMapper.toSnapshot(potBalances);
	}
}
