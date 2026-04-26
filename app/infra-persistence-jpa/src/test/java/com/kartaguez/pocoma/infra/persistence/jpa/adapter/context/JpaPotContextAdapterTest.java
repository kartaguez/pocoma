package com.kartaguez.pocoma.infra.persistence.jpa.adapter.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.kartaguez.pocoma.domain.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.entity.Shareholder;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.Name;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.context.CreateExpenseContext;
import com.kartaguez.pocoma.engine.context.DeletePotContext;
import com.kartaguez.pocoma.engine.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.JpaPotGlobalVersionEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.core.JpaPotHeaderEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.core.JpaShareholderEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.JpaPotGlobalVersionRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.core.JpaPotHeaderRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.core.JpaShareholderRepository;

@DataJpaTest
@Import(JpaPotContextAdapter.class)
class JpaPotContextAdapterTest {

	@Autowired
	private JpaPotContextAdapter adapter;

	@Autowired
	private JpaPotGlobalVersionRepository potGlobalVersionRepository;

	@Autowired
	private JpaPotHeaderRepository potHeaderRepository;

	@Autowired
	private JpaShareholderRepository shareholderRepository;

	@Test
	void loadsCreateExpenseContextFromCurrentPotVersion() {
		PotId potId = PotId.of(UUID.randomUUID());
		UserId creatorId = UserId.of(UUID.randomUUID());
		Shareholder activeShareholder = shareholder(potId);
		Shareholder inactiveShareholder = shareholder(potId);
		Shareholder deletedShareholder = shareholder(potId, true);
		potGlobalVersionRepository.save(JpaPotGlobalVersionEntity.from(new PotGlobalVersion(potId, 3)));
		potHeaderRepository.save(JpaPotHeaderEntity.from(
				PotHeader.reconstitute(potId, Label.of("Old"), creatorId, false),
				1,
				2L));
		potHeaderRepository.save(JpaPotHeaderEntity.from(
				PotHeader.reconstitute(potId, Label.of("Current"), creatorId, false),
				2,
				null));
		shareholderRepository.save(JpaShareholderEntity.from(activeShareholder, 1, null));
		shareholderRepository.save(JpaShareholderEntity.from(inactiveShareholder, 1, 3L));
		shareholderRepository.save(JpaShareholderEntity.from(deletedShareholder, 1, null));

		CreateExpenseContext context = adapter.loadCreateExpenseContext(potId);

		assertEquals(new PotGlobalVersion(potId, 3), context.potGlobalVersion());
		assertEquals(false, context.deleted());
		assertEquals(creatorId, context.creatorId());
		assertEquals(Set.of(activeShareholder.id()), context.shareholderIds());
	}

	@Test
	void loadsDeletedPotContext() {
		PotId potId = PotId.of(UUID.randomUUID());
		UserId creatorId = UserId.of(UUID.randomUUID());
		potGlobalVersionRepository.save(JpaPotGlobalVersionEntity.from(new PotGlobalVersion(potId, 4)));
		potHeaderRepository.save(JpaPotHeaderEntity.from(
				PotHeader.reconstitute(potId, Label.of("Deleted"), creatorId, true),
				4,
				null));

		DeletePotContext context = adapter.loadDeletePotContext(potId);

		assertEquals(new PotGlobalVersion(potId, 4), context.potGlobalVersion());
		assertEquals(true, context.deleted());
		assertEquals(creatorId, context.creatorId());
	}

	@Test
	void rejectsUnknownPotContext() {
		assertThrows(
				BusinessEntityNotFoundException.class,
				() -> adapter.loadUpdatePotDetailsContext(PotId.of(UUID.randomUUID())));
	}

	private static Shareholder shareholder(PotId potId) {
		return shareholder(potId, false);
	}

	private static Shareholder shareholder(PotId potId, boolean deleted) {
		return Shareholder.reconstitute(
				ShareholderId.of(UUID.randomUUID()),
				potId,
				Name.of("Alice"),
				Weight.of(Fraction.of(1, 1)),
				null,
				deleted);
	}

	@SpringBootApplication
	@EntityScan("com.kartaguez.pocoma.infra.persistence.jpa.entity")
	@EnableJpaRepositories("com.kartaguez.pocoma.infra.persistence.jpa.repository")
	static class TestApplication {
	}
}
