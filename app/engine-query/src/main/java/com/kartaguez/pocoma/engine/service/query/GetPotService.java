package com.kartaguez.pocoma.engine.service.query;

import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.domain.pot.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.pot.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.pot.policy.ReadPotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.query.intent.GetPotQuery;
import com.kartaguez.pocoma.engine.port.in.query.result.PotViewSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.usecase.GetPotUseCase;
import com.kartaguez.pocoma.engine.port.out.query.PotQueryPort;
import com.kartaguez.pocoma.engine.security.UserContext;

final class GetPotService implements GetPotUseCase {

	private final PotQueryPort potQueryPort;
	private final ReadPotAuthorizationPolicy readPotAuthorizationPolicy;

	GetPotService(PotQueryPort potQueryPort, ReadPotAuthorizationPolicy readPotAuthorizationPolicy) {
		this.potQueryPort = Objects.requireNonNull(potQueryPort, "potQueryPort must not be null");
		this.readPotAuthorizationPolicy = Objects.requireNonNull(
				readPotAuthorizationPolicy,
				"readPotAuthorizationPolicy must not be null");
	}

	@Override
	public PotViewSnapshot getPot(UserContext userContext, GetPotQuery query) {
		// 1. Validate the incoming query and convert simple input data into domain identifiers.
		Objects.requireNonNull(userContext, "userContext must not be null");
		Objects.requireNonNull(query, "query must not be null");
		PotId potId = PotId.of(query.potId());

		// 2. Resolve the version to read. Missing version means the current pot version.
		long version = query.version().orElseGet(() -> potQueryPort.currentVersion(potId).version());

		// 3. Load the pot header first because it carries the creator used by the read policy.
		PotHeader potHeader = potQueryPort.loadPotHeaderAtVersion(potId, version);

		// 4. Load the shareholders needed by both authorization and the returned view.
		PotShareholders shareholders = potQueryPort.loadPotShareholdersAtVersion(potId, version);

		// 5. Check that the current user is allowed to read this pot at the requested version.
		readPotAuthorizationPolicy.assertCanReadPot(
				userContext.userId(),
				userContext.permissions(),
				potHeader.creatorId(),
				activeShareholderUserIds(shareholders));

		// 6. Return a versioned snapshot to the caller.
		return new PotViewSnapshot(
				QuerySnapshotMapper.toSnapshot(potHeader, version),
				QuerySnapshotMapper.toSnapshot(shareholders, version));
	}

	private static Set<UserId> activeShareholderUserIds(PotShareholders shareholders) {
		return shareholders.shareholders().values().stream()
				.filter(shareholder -> !shareholder.deleted())
				.map(shareholder -> shareholder.userId())
				.collect(java.util.stream.Collectors.toSet());
	}
}
