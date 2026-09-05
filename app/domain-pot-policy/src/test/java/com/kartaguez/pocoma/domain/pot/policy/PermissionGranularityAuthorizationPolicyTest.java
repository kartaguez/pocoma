package com.kartaguez.pocoma.domain.pot.policy;

import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.EXPENSE_UPDATE;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.POT_CREATE;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.POT_DELETE;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.POT_UPDATE;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.POT_VIEW;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.POT_VIEW_ARCHIVE;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.SHAREHOLDER_UPDATE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;

class PermissionGranularityAuthorizationPolicyTest {

	private static final UserId USER_ID = UserId.of(UUID.randomUUID());
	private static final Permission UNKNOWN = new Permission("FUTURE_FEATURE", "VIEW");

	@Test
	void potUpdateAuthorizesDetailsAndIgnoresAnAdditionalUnknownPermission() {
		assertDoesNotThrow(() -> new UpdatePotDetailsAuthorizationPolicy()
				.assertCanUpdatePotDetails(USER_ID, Set.of(POT_UPDATE, UNKNOWN), USER_ID));
	}

	@Test
	void shareholderUpdateAuthorizesDetailsAndWeights() {
		assertDoesNotThrow(() -> new UpdatePotShareholdersDetailsAuthorizationPolicy()
				.assertCanUpdatePotShareholdersDetails(USER_ID, Set.of(SHAREHOLDER_UPDATE), USER_ID, null));
		assertDoesNotThrow(() -> new UpdatePotShareholdersWeightsAuthorizationPolicy()
				.assertCanUpdatePotShareholdersWeights(USER_ID, Set.of(SHAREHOLDER_UPDATE), USER_ID));
	}

	@Test
	void expenseUpdateAuthorizesDetailsAndShares() {
		assertDoesNotThrow(() -> new UpdateExpenseDetailsAuthorizationPolicy()
				.assertCanUpdateExpenseDetails(USER_ID, Set.of(EXPENSE_UPDATE), USER_ID, Set.of()));
		assertDoesNotThrow(() -> new UpdateExpenseSharesAuthorizationPolicy()
				.assertCanUpdateExpenseShares(USER_ID, Set.of(EXPENSE_UPDATE), USER_ID, Set.of()));
	}

	@Test
	void actionsNeverGrantEachOther() {
		for (Permission permission : List.of(POT_VIEW, POT_CREATE, POT_DELETE, POT_VIEW_ARCHIVE)) {
			BusinessRuleViolationException exception = assertThrows(BusinessRuleViolationException.class,
					() -> new UpdatePotDetailsAuthorizationPolicy()
							.assertCanUpdatePotDetails(USER_ID, Set.of(permission), USER_ID));
			assertEquals("MISSING_PERMISSION", exception.ruleCode());
		}
	}

	@Test
	void unknownPermissionAloneGrantsNoPotRight() {
		BusinessRuleViolationException exception = assertThrows(BusinessRuleViolationException.class,
				() -> new UpdatePotDetailsAuthorizationPolicy()
						.assertCanUpdatePotDetails(USER_ID, Set.of(UNKNOWN), USER_ID));

		assertEquals("MISSING_PERMISSION", exception.ruleCode());
	}
}
