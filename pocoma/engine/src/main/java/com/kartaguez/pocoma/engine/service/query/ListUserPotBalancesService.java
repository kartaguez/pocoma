package com.kartaguez.pocoma.engine.service.query;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.entity.Shareholder;
import com.kartaguez.pocoma.domain.projection.Balance;
import com.kartaguez.pocoma.domain.projection.PotBalances;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.engine.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.engine.port.in.query.intent.ListUserPotBalancesQuery;
import com.kartaguez.pocoma.engine.port.in.query.result.UserPotBalanceSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.usecase.ListUserPotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.out.persistence.PotBalancesPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotQueryPort;
import com.kartaguez.pocoma.engine.security.UserContext;

final class ListUserPotBalancesService implements ListUserPotBalancesUseCase {

	private final PotQueryPort potQueryPort;
	private final PotBalancesPort potBalancesPort;

	ListUserPotBalancesService(PotQueryPort potQueryPort, PotBalancesPort potBalancesPort) {
		this.potQueryPort = Objects.requireNonNull(potQueryPort, "potQueryPort must not be null");
		this.potBalancesPort = Objects.requireNonNull(potBalancesPort, "potBalancesPort must not be null");
	}

	@Override
	public List<UserPotBalanceSnapshot> listUserPotBalances(UserContext userContext, ListUserPotBalancesQuery query) {
		// 1. Validate the caller context and the incoming query.
		Objects.requireNonNull(userContext, "userContext must not be null");
		Objects.requireNonNull(query, "query must not be null");

		// 2. Convert the caller into the domain user identifier used by persistence lookups.
		UserId userId = UserId.of(UUID.fromString(userContext.userId()));

		// 3. Start from the current, non-deleted pots accessible to the caller.
		return potQueryPort.listAccessiblePotHeaders(userId).stream()

				// 4. Keep only pots where the caller has a linked shareholder and a projected balance.
				.map(header -> toUserPotBalanceSnapshot(userId, header, query))
				.filter(Objects::nonNull)
				.toList();
	}

	private UserPotBalanceSnapshot toUserPotBalanceSnapshot(
			UserId userId,
			PotQueryPort.VersionedPotHeader header,
			ListUserPotBalancesQuery query) {
		// 1. Resolve the version for this pot. Missing version means this pot's current version.
		long version = query.version().orElse(header.version());

		// 2. Resolve the caller's shareholder at that version before reading their balance.
		return potQueryPort.findLinkedShareholderAtVersion(userId, header.potHeader().id(), version)
				.map(shareholder -> loadUserBalance(header, shareholder, version))
				.orElse(null);
	}

	private UserPotBalanceSnapshot loadUserBalance(
			PotQueryPort.VersionedPotHeader header,
			Shareholder shareholder,
			long version) {
		try {
			// 1. Load the balances projection for this pot and version.
			PotBalances potBalances = potBalancesPort.loadAtVersion(header.potHeader().id(), version);

			// 2. Keep only the balance attached to the caller's linked shareholder.
			Balance balance = potBalances.balances().get(shareholder.id());
			if (balance == null) {
				return null;
			}

			// 3. Return a versioned user balance snapshot to the caller.
			return new UserPotBalanceSnapshot(
					QuerySnapshotMapper.toSnapshot(header.potHeader(), version),
					shareholder.id(),
					QuerySnapshotMapper.toSnapshot(balance),
					version);
		}
		catch (BusinessEntityNotFoundException exception) {
			// Missing projections are ignored for the aggregate user balance listing.
			return null;
		}
	}
}
