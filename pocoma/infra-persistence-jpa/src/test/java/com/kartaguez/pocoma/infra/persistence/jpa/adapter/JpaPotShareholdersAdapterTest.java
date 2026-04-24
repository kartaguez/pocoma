package com.kartaguez.pocoma.infra.persistence.jpa.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.kartaguez.pocoma.domain.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.entity.Shareholder;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Name;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.JpaShareholderEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.JpaShareholderRepository;

@DataJpaTest
@Import(JpaPotShareholdersAdapter.class)
class JpaPotShareholdersAdapterTest {

	@Autowired
	private JpaPotShareholdersAdapter adapter;

	@Autowired
	private JpaShareholderRepository repository;

	@Test
	void loadsEmptyPotShareholdersWhenNoShareholderExists() {
		PotId potId = PotId.of(UUID.randomUUID());

		PotShareholders loaded = adapter.loadActiveAtVersion(potId, 1);

		assertEquals(potId, loaded.potId());
		assertTrue(loaded.shareholders().isEmpty());
	}

	@Test
	void loadsPotShareholdersActiveAtRequestedVersion() {
		PotId potId = PotId.of(UUID.randomUUID());
		Shareholder shareholder = shareholder(potId, "Alice", 1, 2);
		repository.save(JpaShareholderEntity.from(shareholder, 2, null));

		PotShareholders loaded = adapter.loadActiveAtVersion(potId, 3);

		assertSingleShareholderEquals(shareholder, loaded.shareholders().values());
	}

	@Test
	void replacesEmptyPotShareholdersWithActiveRows() {
		PotId potId = PotId.of(UUID.randomUUID());
		PotShareholders next = PotShareholders.reconstitute(potId, Set.of());
		Shareholder shareholder = next.addShareholder(
				Name.of("Alice"),
				Weight.of(Fraction.of(1, 1)),
				null);

		adapter.save(next, new PotGlobalVersion(potId, 1), new PotGlobalVersion(potId, 2));

		PotShareholders loaded = adapter.loadActiveAtVersion(potId, 2);
		assertSingleShareholderEquals(shareholder, loaded.shareholders().values());
	}

	@Test
	void savesOnlyUpdatedPotShareholdersVersion() {
		PotId potId = PotId.of(UUID.randomUUID());
		Shareholder alice = shareholder(potId, "Alice", 1, 1);
		Shareholder bob = shareholder(potId, "Bob", 1, 1);
		repository.save(JpaShareholderEntity.from(alice, 1, null));
		repository.save(JpaShareholderEntity.from(bob, 1, null));
		PotShareholders next = PotShareholders.reconstitute(potId, Set.of(alice, bob));
		next.updateShareholderDetails(alice.id(), Name.of("Alicia"), null);
		Shareholder renamedAlice = next.shareholders().get(alice.id());

		adapter.save(
				next,
				new PotGlobalVersion(potId, 2),
				new PotGlobalVersion(potId, 3));

		PotShareholders oldVersion = adapter.loadActiveAtVersion(potId, 2);
		PotShareholders newVersion = adapter.loadActiveAtVersion(potId, 3);
		assertShareholderEquals(alice, oldVersion.shareholders().get(alice.id()));
		assertShareholderEquals(renamedAlice, newVersion.shareholders().get(alice.id()));
		assertShareholderEquals(bob, newVersion.shareholders().get(bob.id()));

		JpaShareholderEntity activeBob = repository.findActiveAtVersion(potId.value(), 3).stream()
				.filter(entity -> entity.shareholderId().equals(bob.id().value()))
				.findFirst()
				.orElseThrow();
		assertEquals(1, activeBob.startedAtVersion());
		assertEquals(null, activeBob.endedAtVersion());
	}

	@Test
	void savesAddedPotShareholdersWithoutClosingExistingRows() {
		PotId potId = PotId.of(UUID.randomUUID());
		Shareholder bob = shareholder(potId, "Bob", 1, 1);
		repository.save(JpaShareholderEntity.from(bob, 1, null));
		PotShareholders next = PotShareholders.reconstitute(potId, Set.of(bob));
		Shareholder alice = next.addShareholder(
				Name.of("Alice"),
				Weight.of(Fraction.of(1, 1)),
				null);

		adapter.save(
				next,
				new PotGlobalVersion(potId, 2),
				new PotGlobalVersion(potId, 3));

		PotShareholders loaded = adapter.loadActiveAtVersion(potId, 3);
		assertShareholderEquals(bob, loaded.shareholders().get(bob.id()));
		assertShareholderEquals(alice, loaded.shareholders().get(alice.id()));

		JpaShareholderEntity activeBob = repository.findActiveAtVersion(potId.value(), 3).stream()
				.filter(entity -> entity.shareholderId().equals(bob.id().value()))
				.findFirst()
				.orElseThrow();
		assertEquals(1, activeBob.startedAtVersion());
		assertEquals(null, activeBob.endedAtVersion());
	}

	@Test
	void rejectsUpdateWhenNoActiveShareholderCanBeClosed() {
		PotId potId = PotId.of(UUID.randomUUID());
		Shareholder alice = shareholder(potId, "Alice", 1, 1);
		repository.save(JpaShareholderEntity.from(alice, 1, 2L));
		PotShareholders next = PotShareholders.reconstitute(potId, Set.of(alice));
		next.updateShareholderDetails(alice.id(), Name.of("Alicia"), null);

		assertThrows(
				VersionConflictException.class,
				() -> adapter.save(
						next,
						new PotGlobalVersion(potId, 2),
						new PotGlobalVersion(potId, 3)));
	}

	@Test
	void rejectsReplaceWithDifferentPotIds() {
		PotId previousPotId = PotId.of(UUID.randomUUID());
		PotId nextPotId = PotId.of(UUID.randomUUID());

		assertThrows(
				IllegalArgumentException.class,
				() -> adapter.save(
						PotShareholders.reconstitute(previousPotId, Set.of()),
						new PotGlobalVersion(nextPotId, 1),
						new PotGlobalVersion(nextPotId, 2)));
	}

	private static Shareholder shareholder(PotId potId, String name, long numerator, long denominator) {
		return shareholder(ShareholderId.of(UUID.randomUUID()), potId, name, numerator, denominator);
	}

	private static void assertSingleShareholderEquals(Shareholder expected, Collection<Shareholder> actualShareholders) {
		assertEquals(1, actualShareholders.size());
		assertShareholderEquals(expected, actualShareholders.iterator().next());
	}

	private static void assertShareholderEquals(Shareholder expected, Shareholder actual) {
		assertEquals(expected.id(), actual.id());
		assertEquals(expected.potId(), actual.potId());
		assertEquals(expected.name(), actual.name());
		assertEquals(expected.weight(), actual.weight());
		assertEquals(expected.userId(), actual.userId());
		assertEquals(expected.deleted(), actual.deleted());
	}

	private static Shareholder shareholder(
			ShareholderId shareholderId,
			PotId potId,
			String name,
			long numerator,
			long denominator) {
		return Shareholder.reconstitute(
				shareholderId,
				potId,
				Name.of(name),
				Weight.of(Fraction.of(numerator, denominator)),
				UserId.of(UUID.randomUUID()),
				false);
	}

	@SpringBootApplication
	@EntityScan("com.kartaguez.pocoma.infra.persistence.jpa.entity")
	@EnableJpaRepositories("com.kartaguez.pocoma.infra.persistence.jpa.repository")
	static class TestApplication {
	}
}
