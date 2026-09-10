package com.kartaguez.pocoma.engine.read.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.pot.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.pot.association.ExpenseShare;
import com.kartaguez.pocoma.domain.pot.entity.Shareholder;
import com.kartaguez.pocoma.domain.pot.value.Amount;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.PotProjectionExpenseShare;
import com.kartaguez.pocoma.domain.projection.PotProjectionShareholder;
import com.kartaguez.pocoma.domain.projection.PotProjectionStatus;
import com.kartaguez.pocoma.domain.pot.version.PotVersionMetadata;
import com.kartaguez.pocoma.domain.projection.ProjectionGenerationIdentity;
import com.kartaguez.pocoma.domain.projection.ProjectionIdentity;
import com.kartaguez.pocoma.domain.projection.ProjectionType;

class ReconstructPotProjectionServiceTest {

	@Test
	void reconstructsDeletedSnapshotWithCanonicalExactContent() {
		var pot = PotId.of(uuid(1));
		var creator = UserId.of(uuid(2));
		var firstShareholder = ShareholderId.of(uuid(3));
		var secondShareholder = ShareholderId.of(uuid(4));
		var expense = ExpenseId.of(uuid(5));
		var source = new HistoricalPotSnapshotSource.HistoricalPotSnapshot(
				new PotVersionMetadata(pot, 5, Instant.parse("2026-01-01T00:00:00Z")),
				PotHeader.reconstitute(pot, Label.of("Trip"), creator, true),
				List.of(
						Shareholder.reconstitute(secondShareholder, pot, Name.of("B"),
								Weight.of(Fraction.of(2, 4)), null, true),
						Shareholder.reconstitute(firstShareholder, pot, Name.of("A"),
								Weight.of(Fraction.of(1, 2)), creator, false)),
				List.of(new HistoricalPotSnapshotSource.HistoricalExpense(
						ExpenseHeader.reconstitute(expense, pot, firstShareholder,
								Amount.of(Fraction.of(10, 2)), Label.of("Train"), true),
						List.of(
								new ExpenseShare(expense, secondShareholder, Weight.of(Fraction.of(1, 2))),
								new ExpenseShare(expense, firstShareholder, Weight.of(Fraction.of(1, 2)))))));

		var reconstructed = new ReconstructPotProjectionService((id, version) -> source)
				.reconstruct(identity(pot, 5));
		var projection = reconstructed.projection();

		assertEquals(source.versionMetadata(), reconstructed.versionMetadata());
		assertEquals(PotProjectionStatus.DELETED, projection.status());
		assertEquals(List.of(firstShareholder, secondShareholder), projection.shareholders().stream()
				.map(PotProjectionShareholder::shareholderId).toList());
		assertEquals(Fraction.of(1, 2), projection.shareholders().get(1).weight());
		assertTrue(projection.shareholders().get(1).deleted());
		assertEquals(List.of(firstShareholder, secondShareholder), projection.expenses().getFirst().shares().stream()
				.map(PotProjectionExpenseShare::shareholderId).toList());
		assertThrows(UnsupportedOperationException.class,
				() -> projection.shareholders().add(projection.shareholders().getFirst()));
	}

	@Test
	void rejectsImpossibleReferences() {
		var pot = PotId.of(uuid(1));
		var unknown = ShareholderId.of(uuid(9));
		var expense = ExpenseId.of(uuid(5));
		var source = new HistoricalPotSnapshotSource.HistoricalPotSnapshot(
				new PotVersionMetadata(pot, 1, Instant.EPOCH),
				PotHeader.reconstitute(pot, Label.of("Trip"), UserId.of(uuid(2)), false),
				List.of(),
				List.of(new HistoricalPotSnapshotSource.HistoricalExpense(
						ExpenseHeader.reconstitute(expense, pot, unknown, Amount.ZERO, Label.of("X"), false),
						List.of())));

		var exception = assertThrows(HistoricalPotReconstructionException.class,
				() -> new ReconstructPotProjectionService((id, version) -> source)
						.reconstruct(identity(pot, 1)));

		assertEquals("INCOHERENT_POT_HISTORY", exception.failureCode());
	}

	@Test
	void reconstructsEveryVersionIndependentlyAcrossTheRepresentativeTimeline() {
		var pot = PotId.of(uuid(20));
		var creator = UserId.of(uuid(21));
		var user = UserId.of(uuid(22));
		var shareholder = ShareholderId.of(uuid(23));
		var expense = ExpenseId.of(uuid(24));
		var header = PotHeader.reconstitute(pot, Label.of("Trip"), creator, false);
		var active = Shareholder.reconstitute(
				shareholder, pot, Name.of("Alice"), Weight.of(Fraction.ONE), user, false);
		var removed = Shareholder.reconstitute(
				shareholder, pot, Name.of("Alice"), Weight.of(Fraction.ONE), null, true);
		var expenseV3 = new HistoricalPotSnapshotSource.HistoricalExpense(
				ExpenseHeader.reconstitute(expense, pot, shareholder,
						Amount.of(Fraction.of(10, 1)), Label.of("Train"), false),
				List.of(new ExpenseShare(expense, shareholder, Weight.of(Fraction.ONE))));
		var expenseV4 = new HistoricalPotSnapshotSource.HistoricalExpense(
				ExpenseHeader.reconstitute(expense, pot, shareholder,
						Amount.of(Fraction.of(25, 2)), Label.of("Train changed"), false),
				List.of(new ExpenseShare(expense, shareholder, Weight.of(Fraction.of(2, 2)))));
		var history = Map.of(
				1L, snapshot(pot, 1, header, List.of(), List.of()),
				2L, snapshot(pot, 2, header, List.of(active), List.of()),
				3L, snapshot(pot, 3, header, List.of(active), List.of(expenseV3)),
				4L, snapshot(pot, 4, header, List.of(active), List.of(expenseV4)),
				5L, snapshot(pot, 5, header, List.of(removed), List.of(expenseV4)));
		var generation = identity(pot, 1).generation();
		var service = new ReconstructPotProjectionService((id, version) -> history.get(version));

		var version5 = service.reconstruct(new ProjectionIdentity(generation, 5)).projection();
		var version3 = service.reconstruct(new ProjectionIdentity(generation, 3)).projection();
		var version1 = service.reconstruct(new ProjectionIdentity(generation, 1)).projection();
		var version4 = service.reconstruct(new ProjectionIdentity(generation, 4)).projection();
		var version2 = service.reconstruct(new ProjectionIdentity(generation, 2)).projection();

		assertTrue(version1.shareholders().isEmpty());
		assertEquals(Optional.of(user), version2.shareholders().getFirst().userId());
		assertEquals(Fraction.of(10, 1), version3.expenses().getFirst().amount());
		assertEquals(Fraction.of(25, 2), version4.expenses().getFirst().amount());
		assertTrue(version5.shareholders().getFirst().deleted());
		assertEquals(Optional.empty(), version5.shareholders().getFirst().userId());
		assertNotEquals(version2.shareholders(), version5.shareholders());
	}

	private static ProjectionIdentity identity(PotId potId, long version) {
		return new ProjectionIdentity(new ProjectionGenerationIdentity(
				new ProjectionType("READ_POT"),
				new PipelineDefinition(PipelineId.of("read-pot"), 1),
				potId), version);
	}

	private static HistoricalPotSnapshotSource.HistoricalPotSnapshot snapshot(
			PotId pot,
			long version,
			PotHeader header,
			List<Shareholder> shareholders,
			List<HistoricalPotSnapshotSource.HistoricalExpense> expenses) {
		return new HistoricalPotSnapshotSource.HistoricalPotSnapshot(
				new PotVersionMetadata(pot, version, Instant.ofEpochSecond(version)),
				header,
				shareholders,
				expenses);
	}

	private static UUID uuid(int value) {
		return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", value));
	}
}
