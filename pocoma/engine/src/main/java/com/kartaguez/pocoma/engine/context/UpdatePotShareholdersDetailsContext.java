package com.kartaguez.pocoma.engine.context;

import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;

public record UpdatePotShareholdersDetailsContext(
		PotGlobalVersion potGlobalVersion,
		boolean deleted,
		UserId creatorId,
		Set<ShareholderId> shareholderIds) {

	public UpdatePotShareholdersDetailsContext {
		Objects.requireNonNull(potGlobalVersion, "potGlobalVersion must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");
		shareholderIds = Set.copyOf(Objects.requireNonNull(shareholderIds, "shareholderIds must not be null"));
	}

	public void assertUpdatePreconditions(long expectedVersion, Set<ShareholderId> updatedShareholderIds) {
		Set<ShareholderId> checkedUpdatedShareholderIds = Set.copyOf(Objects.requireNonNull(
				updatedShareholderIds,
				"updatedShareholderIds must not be null"));

		if (deleted) {
			throw new BusinessRuleViolationException(
					"POT_ALREADY_DELETED",
					"Shareholders details cannot be updated because the pot is already deleted");
		}

		if (expectedVersion != potGlobalVersion.version()) {
			throw new VersionConflictException("Shareholders details cannot be updated because the expected version is not active");
		}

		for (ShareholderId shareholderId : checkedUpdatedShareholderIds) {
			if (!shareholderIds.contains(shareholderId)) {
				throw new BusinessRuleViolationException(
						"SHAREHOLDER_NOT_PRESENT",
						"Shareholder details cannot be updated because the shareholder does not belong to this pot");
			}
		}
	}
}
