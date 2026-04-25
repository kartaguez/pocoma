package com.kartaguez.pocoma.domain.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

class BalanceMapOperationsTest {

	@Test
	void addsBalancesWithCommonAndDistinctShareholders() {
		ShareholderId aliceId = ShareholderId.of(UUID.randomUUID());
		ShareholderId bobId = ShareholderId.of(UUID.randomUUID());

		Map<ShareholderId, Balance> result = BalanceMapOperations.add(
				Map.of(aliceId, balance(aliceId, Fraction.of(1, 2))),
				Map.of(
						aliceId, balance(aliceId, Fraction.of(1, 3)),
						bobId, balance(bobId, Fraction.of(-5, 1))));

		assertEquals(Fraction.of(5, 6), result.get(aliceId).value());
		assertEquals(Fraction.of(-5, 1), result.get(bobId).value());
	}

	@Test
	void subtractsBalancesWithMissingValuesAsZero() {
		ShareholderId aliceId = ShareholderId.of(UUID.randomUUID());
		ShareholderId bobId = ShareholderId.of(UUID.randomUUID());

		Map<ShareholderId, Balance> result = BalanceMapOperations.subtract(
				Map.of(aliceId, balance(aliceId, Fraction.of(1, 2))),
				Map.of(bobId, balance(bobId, Fraction.of(3, 1))));

		assertEquals(Fraction.of(1, 2), result.get(aliceId).value());
		assertEquals(Fraction.of(-3, 1), result.get(bobId).value());
	}

	@Test
	void keepsZeroResults() {
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());

		Map<ShareholderId, Balance> result = BalanceMapOperations.add(
				Map.of(shareholderId, balance(shareholderId, Fraction.ONE)),
				Map.of(shareholderId, balance(shareholderId, Fraction.of(-1, 1))));

		assertEquals(1, result.size());
		assertEquals(Fraction.ZERO, result.get(shareholderId).value());
	}

	private static Balance balance(ShareholderId shareholderId, Fraction value) {
		return new Balance(shareholderId, value);
	}
}
