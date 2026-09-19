package com.kartaguez.pocoma.engine.pot.read;

import static com.kartaguez.pocoma.engine.pot.read.ProjectionFixtures.CREATOR_UUID;
import static com.kartaguez.pocoma.engine.pot.read.ProjectionFixtures.EXPENSE_UUID;
import static com.kartaguez.pocoma.engine.pot.read.ProjectionFixtures.OTHER_USER_UUID;
import static com.kartaguez.pocoma.engine.pot.read.ProjectionFixtures.POT_UUID;
import static com.kartaguez.pocoma.engine.pot.read.ProjectionFixtures.SHAREHOLDER_A_UUID;
import static com.kartaguez.pocoma.engine.pot.read.ProjectionFixtures.SHAREHOLDER_B_UUID;
import static com.kartaguez.pocoma.engine.pot.read.ProjectionFixtures.USER_UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.value.Amount;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.ArtifactKey;
import com.kartaguez.pocoma.domain.projection.JsonObject;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifact;

class ProjectionInterpretersTest {
	@Test
	void interpretsAuthCreatorAndUserToShareholderRelation() {
		var projection = ProjectionFixtures.authProjection(CREATOR_UUID,
				List.of(ProjectionFixtures.association(USER_UUID, SHAREHOLDER_A_UUID)));

		AuthProjection auth = new AuthProjectionInterpreter().interpret(projection);

		assertTrue(auth.isCreator(new UserId(CREATOR_UUID)));
		assertEquals(Optional.of(new ShareholderId(SHAREHOLDER_A_UUID)),
				auth.shareholderIdFor(new UserId(USER_UUID)));
	}

	@Test
	void authRejectsPayloadUserIdDifferentFromArtifactKey() {
		var projection = ProjectionFixtures.authProjection(CREATOR_UUID,
				List.of(ProjectionFixtures.associationWithKey(OTHER_USER_UUID, USER_UUID, SHAREHOLDER_A_UUID)));

		assertThrows(AuthProjectionInvariantViolationException.class,
				() -> new AuthProjectionInterpreter().interpret(projection));
	}

	@Test
	void authRejectsCreatorPayloadUserIdDifferentFromArtifactKey() {
		var projection = ProjectionFixtures.authProjection(CREATOR_UUID, OTHER_USER_UUID, List.of());

		assertThrows(AuthProjectionInvariantViolationException.class,
				() -> new AuthProjectionInterpreter().interpret(projection));
	}

	@Test
	void authRejectsTwoUsersLinkedToTheSameShareholder() {
		var projection = ProjectionFixtures.authProjection(CREATOR_UUID, List.of(
				ProjectionFixtures.association(USER_UUID, SHAREHOLDER_A_UUID),
				ProjectionFixtures.association(OTHER_USER_UUID, SHAREHOLDER_A_UUID)));

		assertThrows(AuthProjectionInvariantViolationException.class,
				() -> new AuthProjectionInterpreter().interpret(projection));
	}

	@Test
	void interpretsReadPotWithCanonicalNullAndExactFractionsInDeterministicOrder() {
		var projection = ProjectionFixtures.readPotProjection(List.of(
				ProjectionFixtures.expense(SHAREHOLDER_A_UUID, List.of(
						ProjectionFixtures.share(SHAREHOLDER_B_UUID, 2, 7),
						ProjectionFixtures.share(SHAREHOLDER_A_UUID, 1, 3))),
				ProjectionFixtures.shareholder(SHAREHOLDER_B_UUID, "Bob", ProjectionFixtures.jsonNull(), 0, 1),
				ProjectionFixtures.pot(),
				ProjectionFixtures.shareholder(SHAREHOLDER_A_UUID, "Alice",
						ProjectionFixtures.string(USER_UUID), 1, 3)));

		PotView pot = new ReadPotInterpreter().interpret(projection);

		assertEquals(POT_UUID, pot.potId().value());
		assertEquals(List.of(SHAREHOLDER_A_UUID, SHAREHOLDER_B_UUID), pot.shareholders().stream()
				.map(view -> view.shareholderId().value()).toList());
		assertEquals(Optional.of(new UserId(USER_UUID)), pot.shareholders().get(0).userId());
		assertEquals(Optional.empty(), pot.shareholders().get(1).userId());
		assertEquals(new Weight(new Fraction(1, 3)), pot.shareholders().get(0).part());
		assertEquals(new Weight(Fraction.ZERO), pot.shareholders().get(1).part());
		assertEquals(new Amount(new Fraction(2469, 20)), pot.expenses().get(0).amount());
		assertEquals(List.of(SHAREHOLDER_A_UUID, SHAREHOLDER_B_UUID), pot.expenses().get(0).shares().stream()
				.map(view -> view.shareholderId().value()).toList());
		assertEquals(new Weight(new Fraction(1, 3)), pot.expenses().get(0).shares().get(0).part());
		assertEquals(new Weight(new Fraction(2, 7)), pot.expenses().get(0).shares().get(1).part());
	}

	@Test
	void rejectsPotArtifactIdentityMismatch() {
		UUID other = UUID.fromString("00000000-0000-0000-0000-000000000999");
		var projection = ProjectionFixtures.readPotProjection(List.of(ProjectionFixtures.pot(other, POT_UUID)));

		assertThrows(ReadPotInvariantViolationException.class,
				() -> new ReadPotInterpreter().interpret(projection));
	}

	@Test
	void rejectsExpenseWithEmptySharesAfterStructuralValidation() {
		var projection = ProjectionFixtures.readPotProjection(List.of(
				ProjectionFixtures.pot(),
				ProjectionFixtures.shareholder(SHAREHOLDER_A_UUID, "Alice",
						ProjectionFixtures.jsonNull(), 1, 1),
				ProjectionFixtures.expense(SHAREHOLDER_A_UUID, List.of())));

		assertThrows(ReadPotInvariantViolationException.class,
				() -> new ReadPotInterpreter().interpret(projection));
	}

	@Test
	void rejectsUnknownPayerAndUnknownShareholderReference() {
		var unknownPayer = ProjectionFixtures.readPotProjection(List.of(
				ProjectionFixtures.pot(),
				ProjectionFixtures.shareholder(SHAREHOLDER_A_UUID, "Alice",
						ProjectionFixtures.jsonNull(), 1, 1),
				ProjectionFixtures.expense(SHAREHOLDER_B_UUID,
						List.of(ProjectionFixtures.share(SHAREHOLDER_A_UUID, 1, 1)))));
		assertThrows(ReadPotInvariantViolationException.class,
				() -> new ReadPotInterpreter().interpret(unknownPayer));

		var unknownShare = ProjectionFixtures.readPotProjection(List.of(
				ProjectionFixtures.pot(),
				ProjectionFixtures.shareholder(SHAREHOLDER_A_UUID, "Alice",
						ProjectionFixtures.jsonNull(), 1, 1),
				ProjectionFixtures.expense(SHAREHOLDER_A_UUID,
						List.of(ProjectionFixtures.share(SHAREHOLDER_B_UUID, 1, 1)))));
		assertThrows(ReadPotInvariantViolationException.class,
				() -> new ReadPotInterpreter().interpret(unknownShare));
	}

	@Test
	void rejectsShareholderPayloadIdentityDifferentFromArtifactKey() {
		ProjectionArtifact valid = ProjectionFixtures.shareholder(SHAREHOLDER_A_UUID, "Alice",
				ProjectionFixtures.jsonNull(), 1, 1);
		ProjectionArtifact mismatched = new ProjectionArtifact(valid.artifactType(),
				new ArtifactKey(SHAREHOLDER_B_UUID.toString()), (JsonObject) valid.payload());
		var projection = ProjectionFixtures.readPotProjection(List.of(ProjectionFixtures.pot(), mismatched));

		assertThrows(ReadPotInvariantViolationException.class,
				() -> new ReadPotInterpreter().interpret(projection));
	}

	@Test
	void rejectsExpensePayloadIdentityDifferentFromArtifactKey() {
		var projection = ProjectionFixtures.readPotProjection(List.of(
				ProjectionFixtures.pot(),
				ProjectionFixtures.shareholder(SHAREHOLDER_A_UUID, "Alice",
						ProjectionFixtures.jsonNull(), 1, 1),
				ProjectionFixtures.expense(EXPENSE_UUID, SHAREHOLDER_B_UUID, SHAREHOLDER_A_UUID,
						List.of(ProjectionFixtures.share(SHAREHOLDER_A_UUID, 1, 1)))));

		assertThrows(ReadPotInvariantViolationException.class,
				() -> new ReadPotInterpreter().interpret(projection));
	}
}
