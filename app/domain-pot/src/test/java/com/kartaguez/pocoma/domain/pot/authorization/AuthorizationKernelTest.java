package com.kartaguez.pocoma.domain.pot.authorization;

import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.EXPENSE_CREATE;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.EXPENSE_UPDATE;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.EXPENSE_VIEW;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.POT_VIEW;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.SHAREHOLDER_CREATE;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.SHAREHOLDER_UPDATE;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.VIEW_ARCHIVE;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationDenialReason.BUSINESS_POLICY_DENIED;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationDenialReason.CONFIGURATION_ERROR;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationDenialReason.MISSING_CAPABILITY;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationFact.IS_POT_CREATOR;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationFact.IS_POT_MEMBER;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationFact.IS_TARGET_SHAREHOLDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.authorization.RequiredCurrentCapabilities;
import com.kartaguez.pocoma.domain.authorization.TokenCapabilities;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

class AuthorizationKernelTest {

	private final AuthorizationKernel kernel = new AuthorizationKernel();

	@Test
	void requiresEveryCurrentCapabilityRegardlessOfItsOrigin() {
		AuthorizationDecision decision = kernel.decide(
				TokenCapabilities.of(EXPENSE_VIEW),
				RequiredCurrentCapabilities.of(EXPENSE_VIEW, VIEW_ARCHIVE),
				AuthorizationTarget.existing(ExpenseId.of(UUID.randomUUID())),
				AuthorizationFacts.of(IS_POT_MEMBER),
				PotAction.VIEW_EXPENSE);

		assertDenied(decision, MISSING_CAPABILITY);
		assertTrue(kernel.decide(
				TokenCapabilities.of(EXPENSE_VIEW, VIEW_ARCHIVE),
				RequiredCurrentCapabilities.of(EXPENSE_VIEW, VIEW_ARCHIVE),
				AuthorizationTarget.existing(ExpenseId.of(UUID.randomUUID())),
				AuthorizationFacts.of(IS_POT_MEMBER),
				PotAction.VIEW_EXPENSE).isAllowed());
	}

	@Test
	void allowsProspectiveCreationTargetsWithoutPreallocatedIds() {
		assertTrue(kernel.decide(
				TokenCapabilities.of(EXPENSE_CREATE), RequiredCurrentCapabilities.of(EXPENSE_CREATE),
				AuthorizationTarget.prospectiveExpense(), AuthorizationFacts.of(IS_POT_MEMBER),
				PotAction.CREATE_EXPENSE).isAllowed());
		assertTrue(kernel.decide(
				TokenCapabilities.of(SHAREHOLDER_CREATE), RequiredCurrentCapabilities.of(SHAREHOLDER_CREATE),
				AuthorizationTarget.prospectiveShareholder(), AuthorizationFacts.of(IS_POT_CREATOR),
				PotAction.ADD_SHAREHOLDER).isAllowed());
	}

	@Test
	void failsClosedWhenAnExistingTargetIsRequired() {
		assertDenied(kernel.decide(
				TokenCapabilities.of(SHAREHOLDER_UPDATE), RequiredCurrentCapabilities.of(SHAREHOLDER_UPDATE),
				AuthorizationTarget.prospectiveShareholder(), AuthorizationFacts.of(IS_POT_CREATOR),
				PotAction.UPDATE_SHAREHOLDER_DETAILS), CONFIGURATION_ERROR);
		assertDenied(kernel.decide(
				TokenCapabilities.of(EXPENSE_UPDATE), RequiredCurrentCapabilities.of(EXPENSE_UPDATE),
				AuthorizationTarget.prospectiveExpense(), AuthorizationFacts.of(IS_POT_MEMBER),
				PotAction.UPDATE_EXPENSE_DETAILS), CONFIGURATION_ERROR);
	}

	@Test
	void failsClosedForWrongTargetOrMissingBaseRequirement() {
		assertDenied(kernel.decide(
				TokenCapabilities.of(POT_VIEW), RequiredCurrentCapabilities.of(POT_VIEW),
				AuthorizationTarget.existing(ExpenseId.of(UUID.randomUUID())),
				AuthorizationFacts.of(IS_POT_MEMBER), PotAction.VIEW_POT), CONFIGURATION_ERROR);
		assertDenied(kernel.decide(
				TokenCapabilities.of(POT_VIEW), RequiredCurrentCapabilities.of(VIEW_ARCHIVE),
				AuthorizationTarget.existing(PotId.of(UUID.randomUUID())),
				AuthorizationFacts.of(IS_POT_MEMBER), PotAction.VIEW_POT), CONFIGURATION_ERROR);
	}

	@Test
	void failsClosedWhenTheMappingIsMissing() {
		AuthorizationKernel incomplete = new AuthorizationKernel(
				new PotActionRequirements(Map.of()), new TokenCapabilityPolicy(), new PotBusinessAuthorizationPolicy());
		assertDenied(incomplete.decide(
				TokenCapabilities.of(POT_VIEW), RequiredCurrentCapabilities.of(POT_VIEW),
				AuthorizationTarget.existing(PotId.of(UUID.randomUUID())),
				AuthorizationFacts.of(IS_POT_MEMBER), PotAction.VIEW_POT), CONFIGURATION_ERROR);
	}

	@Test
	void usesOneBusinessDenialForDisjunctivePolicies() {
		assertDenied(kernel.decide(
				TokenCapabilities.of(POT_VIEW), RequiredCurrentCapabilities.of(POT_VIEW),
				AuthorizationTarget.existing(PotId.of(UUID.randomUUID())),
				AuthorizationFacts.of(), PotAction.VIEW_POT), BUSINESS_POLICY_DENIED);
		assertDenied(kernel.decide(
				TokenCapabilities.of(SHAREHOLDER_UPDATE), RequiredCurrentCapabilities.of(SHAREHOLDER_UPDATE),
				AuthorizationTarget.existing(ShareholderId.of(UUID.randomUUID())),
				AuthorizationFacts.of(), PotAction.UPDATE_SHAREHOLDER_DETAILS), BUSINESS_POLICY_DENIED);
	}

	@Test
	void authorizesTheTargetShareholderWithoutCreatorFact() {
		assertTrue(kernel.decide(
				TokenCapabilities.of(SHAREHOLDER_UPDATE), RequiredCurrentCapabilities.of(SHAREHOLDER_UPDATE),
				AuthorizationTarget.existing(ShareholderId.of(UUID.randomUUID())),
				AuthorizationFacts.of(IS_TARGET_SHAREHOLDER), PotAction.UPDATE_SHAREHOLDER_DETAILS).isAllowed());
	}

	@Test
	void derivesOnlyFactsSupportedByTheTargetIdentity() {
		PotId potId = PotId.of(UUID.randomUUID());
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
		UserId creator = UserId.of(UUID.randomUUID());
		UserId member = UserId.of(UUID.randomUUID());
		PotAuthorizationRelations relations = new PotAuthorizationRelations(
				potId, creator, Map.of(shareholderId, member));
		PotAuthorizationFactResolver resolver = new PotAuthorizationFactResolver();

		AuthorizationFacts existing = resolver.resolve(
				relations, member, AuthorizationTarget.existing(shareholderId));
		AuthorizationFacts prospective = resolver.resolve(
				relations, member, AuthorizationTarget.prospectiveShareholder());

		assertTrue(existing.contains(IS_POT_MEMBER));
		assertTrue(existing.contains(IS_TARGET_SHAREHOLDER));
		assertTrue(prospective.contains(IS_POT_MEMBER));
		assertFalse(prospective.contains(IS_TARGET_SHAREHOLDER));
	}

	@Test
	void requirementCatalogIsExhaustive() {
		PotActionRequirements requirements = new PotActionRequirements();
		for (PotAction action : PotAction.values()) {
			assertTrue(requirements.find(action).isPresent(), action.name());
		}
		assertEquals(AuthorizationTargetType.POT,
				requirements.find(PotAction.VIEW_BALANCE).orElseThrow().targetType());
	}

	private static void assertDenied(AuthorizationDecision decision, AuthorizationDenialReason reason) {
		AuthorizationDecision.Denied denied = assertInstanceOf(AuthorizationDecision.Denied.class, decision);
		assertEquals(reason, denied.reason());
	}
}
