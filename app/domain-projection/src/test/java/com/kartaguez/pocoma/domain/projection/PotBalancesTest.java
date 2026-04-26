package com.kartaguez.pocoma.domain.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

class PotBalancesTest {

	@Test
	void reconstitutesPotBalances() {
		PotId potId = PotId.of(UUID.randomUUID());
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
		Balance balance = new Balance(shareholderId, Fraction.of(3, 2));

		PotBalances potBalances = new PotBalances(potId, 2, Map.of(shareholderId, balance));

		assertEquals(potId, potBalances.potId());
		assertEquals(2, potBalances.version());
		assertEquals(Map.of(shareholderId, balance), potBalances.balances());
	}

	@Test
	void copiesBalances() {
		PotId potId = PotId.of(UUID.randomUUID());
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
		Map<ShareholderId, Balance> balances = new HashMap<>();
		balances.put(shareholderId, new Balance(shareholderId, Fraction.ONE));

		PotBalances potBalances = new PotBalances(potId, 1, balances);
		balances.clear();

		assertEquals(1, potBalances.balances().size());
		assertThrows(UnsupportedOperationException.class, () -> potBalances.balances().clear());
	}

	@Test
	void rejectsVersionBelowOne() {
		assertThrows(IllegalArgumentException.class, () -> new PotBalances(
				PotId.of(UUID.randomUUID()),
				0,
				Map.of()));
	}

	@Test
	void rejectsInconsistentBalanceKey() {
		ShareholderId key = ShareholderId.of(UUID.randomUUID());
		ShareholderId balanceShareholderId = ShareholderId.of(UUID.randomUUID());

		assertThrows(IllegalArgumentException.class, () -> new PotBalances(
				PotId.of(UUID.randomUUID()),
				1,
				Map.of(key, new Balance(balanceShareholderId, Fraction.ZERO))));
	}
}
