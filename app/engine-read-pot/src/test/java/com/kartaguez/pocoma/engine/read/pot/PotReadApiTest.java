package com.kartaguez.pocoma.engine.read.pot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.value.Amount;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.projection.pot.definition.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.domain.authorization.TokenCapabilities;

class PotReadApiTest {
	private static final PotId POT_ID = new PotId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
	private static final ShareholderId SHAREHOLDER_ID = new ShareholderId(
			UUID.fromString("00000000-0000-0000-0000-000000000002"));
	private static final ExpenseId EXPENSE_ID = new ExpenseId(
			UUID.fromString("00000000-0000-0000-0000-000000000003"));

	@Test
	void viewsAreDefensiveAndUseExactDomainValues() {
		var shareholder = new ShareholderView(SHAREHOLDER_ID, new Name("Alice"), Optional.empty(),
				new Weight(new Fraction(1, 3)));
		var share = new ExpenseShareView(SHAREHOLDER_ID, new Weight(new Fraction(2, 7)));
		var expense = new ExpenseView(EXPENSE_ID, new Label("Dinner"),
				new Amount(new Fraction(12345, 100)), LocalDate.parse("2026-09-19"),
				SHAREHOLDER_ID, List.of(share));
		var shareholders = new ArrayList<>(List.of(shareholder));
		var expenses = new ArrayList<>(List.of(expense));

		var pot = new PotView(POT_ID, 7, new Label("Trip"), shareholders, expenses);
		shareholders.clear();
		expenses.clear();

		assertEquals(List.of(shareholder), pot.shareholders());
		assertEquals(List.of(expense), pot.expenses());
		assertThrows(UnsupportedOperationException.class, () -> pot.shareholders().clear());
		assertThrows(UnsupportedOperationException.class, () -> expense.shares().clear());
	}

	@Test
	void viewsRejectInvalidRequiredValues() {
		var share = new ExpenseShareView(SHAREHOLDER_ID, new Weight(Fraction.ONE));

		assertThrows(IllegalArgumentException.class,
				() -> new PotView(POT_ID, 0, new Label("Trip"), List.of(), List.of()));
		assertThrows(NullPointerException.class,
				() -> new ShareholderView(SHAREHOLDER_ID, new Name("Alice"), null,
						new Weight(Fraction.ONE)));
		assertThrows(IllegalArgumentException.class,
				() -> new ExpenseView(EXPENSE_ID, new Label("Dinner"), Amount.ZERO,
						LocalDate.parse("2026-09-19"), SHAREHOLDER_ID, List.of()));
		assertThrows(NullPointerException.class, () -> new ExpenseShareView(null, share.part()));
		assertThrows(NullPointerException.class, () -> new ReadPotResult.Ready(null));
	}

	@Test
	void resultExposesExactlySixCanonicalStates() {
		assertEquals(6, ReadPotResult.class.getPermittedSubclasses().length);
		var pot = new PotView(POT_ID, 1, new Label("Trip"), List.of(), List.of());
		ProjectionKey key = new ProjectionKey(ReadPotProjectionDefinition.PROJECTION_TYPE,
				ReadPotProjectionDefinition.TARGET_OBJECT_TYPE,
				new TargetObjectId(POT_ID.value().toString()), 1);
		assertEquals(pot, new ReadPotResult.Ready(pot).pot());
		assertEquals(new ReadPotResult.Forbidden(), new ReadPotResult.Forbidden());
		assertEquals(key, new ReadPotResult.AuthFailed(key).projectionKey());
		assertEquals(key, new ReadPotResult.AuthNotReady(key).projectionKey());
		assertEquals(key, new ReadPotResult.ReadPotFailed(key).projectionKey());
		assertEquals(key, new ReadPotResult.ReadPotNotReady(key).projectionKey());
	}

	@Test
	void optionalUserIdMayBePresentOrEmptyButNeverNull() {
		UserId userId = new UserId(UUID.fromString("00000000-0000-0000-0000-000000000004"));
		assertEquals(Optional.of(userId), new ShareholderView(SHAREHOLDER_ID, new Name("Alice"),
				Optional.of(userId), new Weight(Fraction.ZERO)).userId());
		assertEquals(Optional.empty(), new ShareholderView(SHAREHOLDER_ID, new Name("Alice"),
				Optional.empty(), new Weight(Fraction.ZERO)).userId());
	}

	@Test
	void useCaseHasNoImplicitVersionOverload() {
		assertEquals(List.of(List.of(UserId.class, TokenCapabilities.class, PotId.class, long.class)),
				Arrays.stream(ReadPotUseCase.class.getDeclaredMethods())
						.filter(method -> method.getName().equals("read"))
						.map(method -> List.of(method.getParameterTypes()))
						.toList());
	}
}
