package com.kartaguez.pocoma.domain.aggregate;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.kartaguez.pocoma.domain.entity.Shareholder;
import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.Name;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

public final class PotShareholders {

	private final PotId potId;
	private final Map<ShareholderId, Shareholder> shareholders;
	private final Set<ShareholderId> addedShareholderIds;
	private final Set<ShareholderId> updatedShareholderIds;

	public PotShareholders(PotId potId, Set<Shareholder> shareholders) {
		this.potId = Objects.requireNonNull(potId, "potId must not be null");
		this.shareholders = Set.copyOf(Objects.requireNonNull(shareholders, "shareholders must not be null")).stream()
				.collect(Collectors.toMap(Shareholder::id, Function.identity()));
		this.addedShareholderIds = new HashSet<>();
		this.updatedShareholderIds = new HashSet<>();
	}

	public static PotShareholders reconstitute(PotId potId, Set<Shareholder> shareholders) {
		return new PotShareholders(potId, shareholders);
	}

	public Shareholder addShareholder(Name name, Weight weight, UserId userId) {
		Shareholder shareholder = new Shareholder(
				ShareholderId.of(UUID.randomUUID()),
				potId,
				Objects.requireNonNull(name, "name must not be null"),
				Objects.requireNonNull(weight, "weight must not be null"),
				userId,
				false);

		shareholders.put(shareholder.id(), shareholder);
		addedShareholderIds.add(shareholder.id());
		return shareholder;
	}

	public void updateShareholderDetails(ShareholderId shareholderId, Name name, UserId userId) {
		Objects.requireNonNull(shareholderId, "shareholderId must not be null");
		Objects.requireNonNull(name, "name must not be null");

		Shareholder shareholder = requireShareholder(shareholderId);
		shareholders.put(
				shareholderId,
				new Shareholder(
						shareholder.id(),
						shareholder.potId(),
						name,
						shareholder.weight(),
						userId,
						shareholder.deleted()));
		markAsUpdated(shareholderId);
	}

	public void updateShareholderWeight(ShareholderId shareholderId, Weight weight) {
		Objects.requireNonNull(shareholderId, "shareholderId must not be null");
		Objects.requireNonNull(weight, "weight must not be null");

		Shareholder shareholder = requireShareholder(shareholderId);
		shareholders.put(
				shareholderId,
				new Shareholder(
						shareholder.id(),
						shareholder.potId(),
						shareholder.name(),
						weight,
						shareholder.userId(),
						shareholder.deleted()));
		markAsUpdated(shareholderId);
	}

	public PotId potId() {
		return potId;
	}

	public Map<ShareholderId, Shareholder> shareholders() {
		return Map.copyOf(shareholders);
	}

	public Set<ShareholderId> addedShareholderIds() {
		return Set.copyOf(addedShareholderIds);
	}

	public Set<ShareholderId> updatedShareholderIds() {
		return Set.copyOf(updatedShareholderIds);
	}

	private Shareholder requireShareholder(ShareholderId shareholderId) {
		Shareholder shareholder = shareholders.get(shareholderId);
		if (shareholder == null) {
			throw new BusinessRuleViolationException(
					"SHAREHOLDER_NOT_PRESENT",
					"Shareholder does not belong to this pot");
		}
		return shareholder;
	}

	private void markAsUpdated(ShareholderId shareholderId) {
		if (!addedShareholderIds.contains(shareholderId)) {
			updatedShareholderIds.add(shareholderId);
		}
	}
}
