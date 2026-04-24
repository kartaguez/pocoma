package com.kartaguez.pocoma.domain.entity;

import java.util.Objects;

import com.kartaguez.pocoma.domain.value.Name;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

public final class Shareholder {

	private final ShareholderId id;
	private final PotId potId;
	private final Name name;
	private final Weight weight;
	private final UserId userId;
	private final boolean deleted;

	public Shareholder(ShareholderId id, PotId potId, Name name, Weight weight, UserId userId, boolean deleted) {
		this.id = Objects.requireNonNull(id, "id must not be null");
		this.potId = Objects.requireNonNull(potId, "potId must not be null");
		this.name = Objects.requireNonNull(name, "name must not be null");
		this.weight = Objects.requireNonNull(weight, "weight must not be null");
		this.userId = userId;
		this.deleted = deleted;
	}

	public static Shareholder reconstitute(
			ShareholderId id,
			PotId potId,
			Name name,
			Weight weight,
			UserId userId,
			boolean deleted) {
		return new Shareholder(id, potId, name, weight, userId, deleted);
	}

	public ShareholderId id() {
		return id;
	}

	public PotId potId() {
		return potId;
	}

	public Name name() {
		return name;
	}

	public Weight weight() {
		return weight;
	}

	public UserId userId() {
		return userId;
	}

	public boolean deleted() {
		return deleted;
	}
}
