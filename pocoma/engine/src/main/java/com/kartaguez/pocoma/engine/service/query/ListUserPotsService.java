package com.kartaguez.pocoma.engine.service.query;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.engine.port.in.command.result.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.usecase.ListUserPotsUseCase;
import com.kartaguez.pocoma.engine.port.out.persistence.PotQueryPort;
import com.kartaguez.pocoma.engine.security.UserContext;

final class ListUserPotsService implements ListUserPotsUseCase {

	private final PotQueryPort potQueryPort;

	ListUserPotsService(PotQueryPort potQueryPort) {
		this.potQueryPort = Objects.requireNonNull(potQueryPort, "potQueryPort must not be null");
	}

	@Override
	public List<PotHeaderSnapshot> listUserPots(UserContext userContext) {
		// 1. Validate the caller context and convert it into the domain user identifier.
		Objects.requireNonNull(userContext, "userContext must not be null");
		UserId userId = UserId.of(UUID.fromString(userContext.userId()));

		// 2. Load the current, non-deleted pots accessible through creator or linked shareholder.
		return potQueryPort.listAccessiblePotHeaders(userId).stream()

				// 3. Return versioned pot header snapshots to the caller.
				.map(header -> QuerySnapshotMapper.toSnapshot(header.potHeader(), header.version()))
				.toList();
	}
}
