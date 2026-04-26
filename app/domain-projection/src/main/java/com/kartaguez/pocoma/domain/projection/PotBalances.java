package com.kartaguez.pocoma.domain.projection;

import java.util.Map;
import java.util.Objects;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

public record PotBalances(PotId potId, long version, Map<ShareholderId, Balance> balances) {

	public PotBalances {
		Objects.requireNonNull(potId, "potId must not be null");
		Objects.requireNonNull(balances, "balances must not be null");
		if (version < 1) {
			throw new IllegalArgumentException("version must be greater than or equal to 1");
		}
		balances.forEach(PotBalances::assertConsistentEntry);
		balances = Map.copyOf(balances);
	}

	private static void assertConsistentEntry(ShareholderId shareholderId, Balance balance) {
		Objects.requireNonNull(shareholderId, "balance shareholderId key must not be null");
		Objects.requireNonNull(balance, "balance must not be null");
		if (!shareholderId.equals(balance.shareholderId())) {
			throw new IllegalArgumentException("balance key must match balance shareholderId");
		}
	}
}
