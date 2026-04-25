package com.kartaguez.pocoma.engine.service.query;

import java.util.Objects;

import com.kartaguez.pocoma.domain.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.policy.ReadPotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.query.intent.GetPotQuery;
import com.kartaguez.pocoma.engine.port.in.query.result.PotViewSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.usecase.GetPotUseCase;
import com.kartaguez.pocoma.engine.port.out.persistence.PotQueryPort;
import com.kartaguez.pocoma.engine.security.UserContext;

final class GetPotService implements GetPotUseCase {

	private final PotQueryPort potQueryPort;
	private final QueryAuthorizationService authorizationService;

	GetPotService(PotQueryPort potQueryPort, ReadPotAuthorizationPolicy readPotAuthorizationPolicy) {
		this.potQueryPort = Objects.requireNonNull(potQueryPort, "potQueryPort must not be null");
		this.authorizationService = new QueryAuthorizationService(potQueryPort, readPotAuthorizationPolicy);
	}

	@Override
	public PotViewSnapshot getPot(UserContext userContext, GetPotQuery query) {
		// 1. Validate the incoming query and convert simple input data into domain identifiers.
		Objects.requireNonNull(query, "query must not be null");
		PotId potId = PotId.of(query.potId());

		// 2. Resolve the version to read. Missing version means the current pot version.
		long version = query.version().orElseGet(() -> potQueryPort.currentVersion(potId).version());

		// 3. Load the pot header first because it carries the creator used by the read policy.
		PotHeader potHeader = potQueryPort.loadPotHeaderAtVersion(potId, version);

		// 4. Check that the current user is allowed to read this pot at the requested version.
		authorizationService.assertCanRead(userContext, potHeader, potId, version);

		// 5. Load the rest of the pot view once authorization has succeeded.
		PotShareholders shareholders = potQueryPort.loadPotShareholdersAtVersion(potId, version);

		// 6. Return a versioned snapshot to the caller.
		return new PotViewSnapshot(
				QuerySnapshotMapper.toSnapshot(potHeader, version),
				QuerySnapshotMapper.toSnapshot(shareholders, version));
	}
}
