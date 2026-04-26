package com.kartaguez.pocoma.infra.persistence.jpa.adapter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.kartaguez.pocoma.domain.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.core.JpaPotHeaderEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.core.JpaPotHeaderRepository;

@DataJpaTest
@Import(JpaPotHeaderAdapter.class)
class JpaPotHeaderAdapterTest {

	@Autowired
	private JpaPotHeaderAdapter adapter;

	@Autowired
	private JpaPotHeaderRepository repository;

	@Test
	void savesPotHeader() {
		PotId potId = PotId.of(UUID.randomUUID());
		UserId creatorId = UserId.of(UUID.randomUUID());

		adapter.saveNew(PotHeader.reconstitute(potId, Label.of("Holiday"), creatorId, false), 1);

		PotHeader loaded = adapter.loadActiveAtVersion(potId, 1);
		assertEquals(potId, loaded.id());
		assertEquals(Label.of("Holiday"), loaded.label());
		assertEquals(creatorId, loaded.creatorId());
		assertFalse(loaded.deleted());
		assertEquals(1, repository.findActiveAtVersion(potId.value(), 1).orElseThrow().startedAtVersion());
		assertNull(repository.findActiveAtVersion(potId.value(), 1).orElseThrow().endedAtVersion());
	}

	@Test
	void loadsPotHeaderActiveAtRequestedVersion() {
		PotId potId = PotId.of(UUID.randomUUID());
		UserId creatorId = UserId.of(UUID.randomUUID());
		repository.save(JpaPotHeaderEntity.from(PotHeader.reconstitute(potId, Label.of("Initial"), creatorId, false), 1, 3L));
		adapter.saveNew(PotHeader.reconstitute(potId, Label.of("Renamed"), creatorId, true), 3);

		PotHeader loaded = adapter.loadActiveAtVersion(potId, 4);

		assertEquals(Label.of("Renamed"), loaded.label());
		assertTrue(loaded.deleted());
		assertEquals(3, repository.findActiveAtVersion(potId.value(), 4).orElseThrow().startedAtVersion());
		assertNull(repository.findActiveAtVersion(potId.value(), 4).orElseThrow().endedAtVersion());
	}

	@Test
	void rejectsLoadingUnknownActivePotHeader() {
		PotId potId = PotId.of(UUID.randomUUID());

		BusinessEntityNotFoundException exception = assertThrows(
				BusinessEntityNotFoundException.class,
				() -> adapter.loadActiveAtVersion(potId, 1));

		assertEquals("POT_HEADER", exception.entityCode());
	}

	@Test
	void replacesActivePotHeaderVersion() {
		PotId potId = PotId.of(UUID.randomUUID());
		UserId creatorId = UserId.of(UUID.randomUUID());
		PotHeader active = PotHeader.reconstitute(potId, Label.of("Initial"), creatorId, false);
		adapter.saveNew(active, 1);

		PotHeader next = PotHeader.reconstitute(potId, Label.of("Renamed"), creatorId, false);
		adapter.save(next, new PotGlobalVersion(potId, 1), new PotGlobalVersion(potId, 2));

		JpaPotHeaderEntity oldVersion = repository.findActiveAtVersion(potId.value(), 1).orElseThrow();
		PotHeader newVersion = adapter.loadActiveAtVersion(potId, 2);
		assertEquals(2L, oldVersion.endedAtVersion());
		assertEquals(Label.of("Initial"), Label.of(oldVersion.label()));
		assertEquals(Label.of("Renamed"), newVersion.label());
		assertNull(repository.findActiveAtVersion(potId.value(), 2).orElseThrow().endedAtVersion());
	}

	@Test
	void replacesPotHeaderThatStartedBeforeCurrentGlobalVersion() {
		PotId potId = PotId.of(UUID.randomUUID());
		UserId creatorId = UserId.of(UUID.randomUUID());
		PotHeader active = PotHeader.reconstitute(potId, Label.of("Initial"), creatorId, false);
		adapter.saveNew(active, 2);

		PotHeader next = PotHeader.reconstitute(potId, Label.of("Renamed"), creatorId, false);
		adapter.save(next, new PotGlobalVersion(potId, 3), new PotGlobalVersion(potId, 4));

		PotHeader stillActiveAtCurrentVersion = adapter.loadActiveAtVersion(potId, 3);
		PotHeader newVersion = adapter.loadActiveAtVersion(potId, 4);
		assertEquals(Label.of("Initial"), stillActiveAtCurrentVersion.label());
		assertEquals(4L, repository.findActiveAtVersion(potId.value(), 3).orElseThrow().endedAtVersion());
		assertEquals(Label.of("Renamed"), newVersion.label());
		assertNull(repository.findActiveAtVersion(potId.value(), 4).orElseThrow().endedAtVersion());
	}

	@Test
	void rejectsReplaceWhenPreviousVersionIsNoLongerActive() {
		PotId potId = PotId.of(UUID.randomUUID());
		UserId creatorId = UserId.of(UUID.randomUUID());
		repository.save(JpaPotHeaderEntity.from(PotHeader.reconstitute(potId, Label.of("Initial"), creatorId, false), 1, 2L));

		VersionConflictException exception = assertThrows(
				VersionConflictException.class,
				() -> adapter.save(
						PotHeader.reconstitute(potId, Label.of("Renamed"), creatorId, false),
						new PotGlobalVersion(potId, 2),
						new PotGlobalVersion(potId, 3)));

		assertEquals("POT_VERSION_CONFLICT", exception.conflictCode());
		assertEquals(1, repository.findActiveAtVersion(potId.value(), 1).orElseThrow().startedAtVersion());
	}

	@Test
	void rejectsReplaceWithDifferentPotIds() {
		PotId previousPotId = PotId.of(UUID.randomUUID());
		PotId nextPotId = PotId.of(UUID.randomUUID());

		assertThrows(
				IllegalArgumentException.class,
				() -> adapter.save(
						PotHeader.reconstitute(previousPotId, Label.of("Initial"), UserId.of(UUID.randomUUID()), false),
						new PotGlobalVersion(nextPotId, 1),
						new PotGlobalVersion(nextPotId, 2)));
	}

	@SpringBootApplication
	@EntityScan("com.kartaguez.pocoma.infra.persistence.jpa.entity")
	@EnableJpaRepositories("com.kartaguez.pocoma.infra.persistence.jpa.repository")
	static class TestApplication {
	}
}
