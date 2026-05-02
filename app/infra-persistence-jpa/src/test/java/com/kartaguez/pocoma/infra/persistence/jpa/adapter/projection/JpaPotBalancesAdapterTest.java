package com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.kartaguez.pocoma.domain.projection.Balance;
import com.kartaguez.pocoma.domain.projection.PotBalances;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.engine.model.PotBalanceProjectionState;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.projection.JpaPotBalanceProjectionStateEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.projection.JpaPotBalanceProjectionStateRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.projection.JpaPotBalanceRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.projection.JpaPotBalanceVersionRepository;

@DataJpaTest
@Import(JpaPotBalancesAdapter.class)
class JpaPotBalancesAdapterTest {

	@Autowired
	private JpaPotBalancesAdapter adapter;

	@Autowired
	private JpaPotBalanceRepository balanceRepository;

	@Autowired
	private JpaPotBalanceVersionRepository versionRepository;

	@Autowired
	private JpaPotBalanceProjectionStateRepository stateRepository;

	@Test
	void savesInitialBalancesAndProjectionState() {
		PotId potId = PotId.of(UUID.randomUUID());
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
		PotBalances potBalances = new PotBalances(
				potId,
				1,
				Map.of(shareholderId, new Balance(shareholderId, Fraction.of(-3, 2))));

		adapter.saveInitial(potBalances);

		assertEquals(new PotBalanceProjectionState(potId, 1), adapter.loadProjectionState(potId).orElseThrow());
		assertEquals(potBalances, adapter.loadAtVersion(potId, 1));
		assertEquals(1, balanceRepository.findByPotIdAndVersion(potId.value(), 1).size());
		assertEquals(true, versionRepository.existsByPotIdAndVersion(potId.value(), 1));
	}

	@Test
	void savesEmptyVersionSoItCanBeLoaded() {
		PotId potId = PotId.of(UUID.randomUUID());
		PotBalances potBalances = new PotBalances(potId, 1, Map.of());

		adapter.saveInitial(potBalances);

		assertEquals(potBalances, adapter.loadAtVersion(potId, 1));
	}

	@Test
	void savesNextBalancesWhenProjectionStateMatchesExpectedVersion() {
		PotId potId = PotId.of(UUID.randomUUID());
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
		stateRepository.save(JpaPotBalanceProjectionStateEntity.from(new PotBalanceProjectionState(potId, 2)));
		PotBalances potBalances = new PotBalances(
				potId,
				3,
				Map.of(shareholderId, new Balance(shareholderId, Fraction.of(7, 1))));

		adapter.save(potBalances, 2);

		assertEquals(new PotBalanceProjectionState(potId, 3), adapter.loadProjectionState(potId).orElseThrow());
		assertEquals(potBalances, adapter.loadAtVersion(potId, 3));
		assertEquals(true, versionRepository.existsByPotIdAndVersion(potId.value(), 3));
	}

	@Test
	void rejectsSaveWhenProjectionStateDoesNotMatchExpectedVersion() {
		PotId potId = PotId.of(UUID.randomUUID());
		stateRepository.save(JpaPotBalanceProjectionStateEntity.from(new PotBalanceProjectionState(potId, 4)));
		PotBalances potBalances = new PotBalances(potId, 5, Map.of());

		assertThrows(VersionConflictException.class, () -> adapter.save(potBalances, 3));
		assertEquals(new PotBalanceProjectionState(potId, 4), adapter.loadProjectionState(potId).orElseThrow());
	}

	@Test
	void savesFullHistoricalVersionWithoutRegressingProjectionState() {
		PotId potId = PotId.of(UUID.randomUUID());
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
		stateRepository.save(JpaPotBalanceProjectionStateEntity.from(new PotBalanceProjectionState(potId, 5)));
		PotBalances potBalances = new PotBalances(
				potId,
				3,
				Map.of(shareholderId, new Balance(shareholderId, Fraction.of(2, 1))));

		adapter.saveFull(potBalances);

		assertEquals(new PotBalanceProjectionState(potId, 5), adapter.loadProjectionState(potId).orElseThrow());
		assertEquals(potBalances, adapter.loadAtVersion(potId, 3));
	}

	@Test
	void savesFullFutureVersionAndAdvancesProjectionState() {
		PotId potId = PotId.of(UUID.randomUUID());
		stateRepository.save(JpaPotBalanceProjectionStateEntity.from(new PotBalanceProjectionState(potId, 2)));
		PotBalances potBalances = new PotBalances(potId, 4, Map.of());

		adapter.saveFull(potBalances);

		assertEquals(new PotBalanceProjectionState(potId, 4), adapter.loadProjectionState(potId).orElseThrow());
		assertEquals(potBalances, adapter.loadAtVersion(potId, 4));
	}

	@Test
	void saveFullIsIdempotentWhenVersionAlreadyExists() {
		PotId potId = PotId.of(UUID.randomUUID());
		PotBalances potBalances = new PotBalances(potId, 1, Map.of());
		adapter.saveFull(potBalances);

		adapter.saveFull(potBalances);

		assertEquals(new PotBalanceProjectionState(potId, 1), adapter.loadProjectionState(potId).orElseThrow());
		assertEquals(potBalances, adapter.loadAtVersion(potId, 1));
	}

	@Test
	void rejectsLoadingUnknownVersion() {
		assertThrows(
				BusinessEntityNotFoundException.class,
				() -> adapter.loadAtVersion(PotId.of(UUID.randomUUID()), 42));
	}

	@SpringBootApplication
	@EntityScan("com.kartaguez.pocoma.infra.persistence.jpa.entity")
	@EnableJpaRepositories("com.kartaguez.pocoma.infra.persistence.jpa.repository")
	static class TestApplication {
	}
}
