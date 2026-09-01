package com.kartaguez.pocoma.pipeline.balance.projection;

import static java.util.Objects.requireNonNull;

import java.util.Map;

import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.balance.Balance;

public record BalanceProjectionArtifact(BalanceProjectionIdentity identity,
		Map<ShareholderId, Balance> balances) {
	public BalanceProjectionArtifact {
		requireNonNull(identity, "identity must not be null");
		balances = Map.copyOf(requireNonNull(balances, "balances must not be null"));
		balances.forEach((id, balance) -> {
			if (!id.equals(balance.shareholderId())) throw new IllegalArgumentException("balance key mismatch");
		});
	}
}
