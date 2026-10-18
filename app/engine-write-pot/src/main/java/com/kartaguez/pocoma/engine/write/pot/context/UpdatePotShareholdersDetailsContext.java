package com.kartaguez.pocoma.engine.write.pot.context;

import java.util.Objects;
import java.util.Map;
import java.util.Set;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.pot.version.PotGlobalVersion;

public record UpdatePotShareholdersDetailsContext(
		PotGlobalVersion potGlobalVersion,
		boolean deleted,
		UserId creatorId,
		Set<ShareholderId> shareholderIds,
		Map<ShareholderId, UserId> shareholderUsers,
		Map<ShareholderId, Long> shareholderStartedAtVersions) {

	public UpdatePotShareholdersDetailsContext {
		Objects.requireNonNull(potGlobalVersion, "potGlobalVersion must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");
		shareholderIds = Set.copyOf(Objects.requireNonNull(shareholderIds, "shareholderIds must not be null"));
		shareholderUsers = Map.copyOf(Objects.requireNonNull(shareholderUsers, "shareholderUsers must not be null"));
		shareholderStartedAtVersions = Map.copyOf(Objects.requireNonNull(shareholderStartedAtVersions));
		if (!shareholderStartedAtVersions.keySet().equals(shareholderIds)) {
			throw new IllegalArgumentException("Every current shareholder must have a temporal witness");
		}
		for (long started : shareholderStartedAtVersions.values()) {
			if (started < 1 || started > potGlobalVersion.version()) throw new IllegalArgumentException("Invalid shareholder temporal witness");
		}
	}

	public void assertUpdatePreconditions(long observedVersion, Set<ShareholderId> updatedShareholderIds) {
		if (observedVersion < 1 || observedVersion > potGlobalVersion.version()) {
			throw new BusinessRuleViolationException("INVALID_OBSERVED_VERSION", "Observed version must be between 1 and the working version");
		}
		Set<ShareholderId> checkedUpdatedShareholderIds = Set.copyOf(Objects.requireNonNull(
				updatedShareholderIds,
				"updatedShareholderIds must not be null"));

		if (deleted) {
			throw new BusinessRuleViolationException(
					"POT_ALREADY_DELETED",
					"Shareholders details cannot be updated because the pot is already deleted");
		}

		for (ShareholderId shareholderId : checkedUpdatedShareholderIds) {
			if (!shareholderIds.contains(shareholderId)) {
				throw new BusinessRuleViolationException(
						"SHAREHOLDER_NOT_PRESENT",
						"Shareholder details cannot be updated because the shareholder does not belong to this pot");
			}
		}
		for (ShareholderId shareholderId : checkedUpdatedShareholderIds) {
			if (shareholderStartedAtVersions.get(shareholderId) > observedVersion) {
				throw new BusinessRuleViolationException("OBSOLETE_COMMAND", "A targeted shareholder changed since the observed version");
			}
		}
	}
}
