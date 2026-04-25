package com.kartaguez.pocoma.engine.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.entity.Shareholder;
import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.policy.ReadPotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.Name;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.in.query.intent.GetPotQuery;
import com.kartaguez.pocoma.engine.port.in.query.result.PotViewSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.usecase.GetPotUseCase;
import com.kartaguez.pocoma.engine.port.out.persistence.PotQueryPort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.security.UserContext;

class GetPotServiceTest {

	@Test
	void returnsPotViewForCreatorAtCurrentVersion() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		PotId potId = PotId.of(UUID.randomUUID());
		FakePotQueryPort potQueryPort = new FakePotQueryPort(potId, creatorId, Optional.empty());
		FakeTransactionRunner transactionRunner = new FakeTransactionRunner();
		GetPotUseCase useCase = QueryUseCaseFactory.getPotUseCase(
				potQueryPort,
				new ReadPotAuthorizationPolicy(),
				transactionRunner);

		PotViewSnapshot snapshot = useCase.getPot(
				new UserContext(creatorId.value().toString()),
				new GetPotQuery(potId.value()));

		assertEquals(potId, snapshot.header().id());
		assertEquals(3, snapshot.header().version());
		assertEquals(1, snapshot.shareholders().shareholders().size());
		assertEquals(1, transactionRunner.runCount);
	}

	@Test
	void allowsLinkedShareholderAtRequestedVersion() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		UserId linkedUserId = UserId.of(UUID.randomUUID());
		PotId potId = PotId.of(UUID.randomUUID());
		FakePotQueryPort potQueryPort = new FakePotQueryPort(potId, creatorId, Optional.of(linkedUserId));
		GetPotUseCase useCase = QueryUseCaseFactory.getPotUseCase(
				potQueryPort,
				new ReadPotAuthorizationPolicy(),
				new FakeTransactionRunner());

		PotViewSnapshot snapshot = useCase.getPot(
				new UserContext(linkedUserId.value().toString()),
				new GetPotQuery(potId.value(), java.util.OptionalLong.of(2)));

		assertEquals(2, snapshot.header().version());
	}

	@Test
	void rejectsUnlinkedUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		PotId potId = PotId.of(UUID.randomUUID());
		FakePotQueryPort potQueryPort = new FakePotQueryPort(potId, creatorId, Optional.empty());
		GetPotUseCase useCase = QueryUseCaseFactory.getPotUseCase(
				potQueryPort,
				new ReadPotAuthorizationPolicy(),
				new FakeTransactionRunner());

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> useCase.getPot(new UserContext(UUID.randomUUID().toString()), new GetPotQuery(potId.value())));

		assertEquals("POT_READ_FORBIDDEN", exception.ruleCode());
	}

	private static final class FakeTransactionRunner implements TransactionRunner {

		private int runCount;

		@Override
		public <T> T runInTransaction(Supplier<T> action) {
			runCount++;
			return action.get();
		}

		@Override
		public void runAfterCommit(Runnable action) {
			action.run();
		}
	}

	private static final class FakePotQueryPort implements PotQueryPort {

		private final PotId potId;
		private final UserId creatorId;
		private final Optional<UserId> linkedUserId;
		private final Shareholder shareholder;

		private FakePotQueryPort(PotId potId, UserId creatorId, Optional<UserId> linkedUserId) {
			this.potId = potId;
			this.creatorId = creatorId;
			this.linkedUserId = linkedUserId;
			this.shareholder = Shareholder.reconstitute(
					ShareholderId.of(UUID.randomUUID()),
					this.potId,
					Name.of("Alice"),
					Weight.of(Fraction.of(1, 1)),
					linkedUserId.orElse(null),
					false);
		}

		@Override
		public PotGlobalVersion currentVersion(PotId potId) {
			return new PotGlobalVersion(potId, 3);
		}

		@Override
		public PotHeader loadPotHeaderAtVersion(PotId potId, long version) {
			return PotHeader.reconstitute(potId, Label.of("Trip"), creatorId, false);
		}

		@Override
		public PotShareholders loadPotShareholdersAtVersion(PotId potId, long version) {
			return PotShareholders.reconstitute(potId, Set.of(shareholder));
		}

		@Override
		public List<VersionedPotHeader> listAccessiblePotHeaders(UserId userId) {
			return List.of();
		}

		@Override
		public Optional<Shareholder> findLinkedShareholderAtVersion(UserId userId, PotId potId, long version) {
			return linkedUserId.filter(userId::equals).map(ignored -> shareholder);
		}
	}
}
