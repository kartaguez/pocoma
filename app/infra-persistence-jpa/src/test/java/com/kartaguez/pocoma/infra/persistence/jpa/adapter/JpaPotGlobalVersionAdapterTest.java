package com.kartaguez.pocoma.infra.persistence.jpa.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.JpaPotGlobalVersionRepository;

@DataJpaTest
@Import(JpaPotGlobalVersionAdapter.class)
class JpaPotGlobalVersionAdapterTest {

	@Autowired
	private JpaPotGlobalVersionAdapter adapter;

	@Autowired
	private JpaPotGlobalVersionRepository repository;

	@Test
	void savesPotGlobalVersion() {
		PotId potId = PotId.of(UUID.randomUUID());

		adapter.save(new PotGlobalVersion(potId, 1));

		assertEquals(1, repository.findById(potId.value()).orElseThrow().version());
	}

	@Test
	void updatesPotGlobalVersionWhenExpectedVersionIsActive() {
		PotId potId = PotId.of(UUID.randomUUID());
		adapter.save(new PotGlobalVersion(potId, 3));

		adapter.updateIfActive(new PotGlobalVersion(potId, 3), new PotGlobalVersion(potId, 4));

		assertEquals(4, repository.findById(potId.value()).orElseThrow().version());
	}

	@Test
	void rejectsUpdateWhenExpectedVersionIsNotActive() {
		PotId potId = PotId.of(UUID.randomUUID());
		adapter.save(new PotGlobalVersion(potId, 4));

		VersionConflictException exception = assertThrows(
				VersionConflictException.class,
				() -> adapter.updateIfActive(new PotGlobalVersion(potId, 3), new PotGlobalVersion(potId, 4)));

		assertEquals("POT_VERSION_CONFLICT", exception.conflictCode());
		assertEquals(4, repository.findById(potId.value()).orElseThrow().version());
	}

	@Test
	void rejectsUpdateWithDifferentPotIds() {
		PotId expectedPotId = PotId.of(UUID.randomUUID());
		PotId nextPotId = PotId.of(UUID.randomUUID());

		assertThrows(
				IllegalArgumentException.class,
				() -> adapter.updateIfActive(
						new PotGlobalVersion(expectedPotId, 1),
						new PotGlobalVersion(nextPotId, 2)));
	}

	@SpringBootApplication
	@EntityScan("com.kartaguez.pocoma.infra.persistence.jpa.entity")
	@EnableJpaRepositories("com.kartaguez.pocoma.infra.persistence.jpa.repository")
	static class TestApplication {
	}
}
