package com.kartaguez.pocoma.domain.aggregate;

import java.util.Objects;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.PotId;

public final class PotHeader {

	private final PotId id;
	private Label label;
	private final UserId creatorId;
	private boolean deleted;

	private PotHeader(PotId id, Label label, UserId creatorId, boolean deleted) {
		this.id = Objects.requireNonNull(id, "id must not be null");
		this.label = Objects.requireNonNull(label, "label must not be null");
		this.creatorId = Objects.requireNonNull(creatorId, "creatorId must not be null");
		this.deleted = deleted;
	}

	public static PotHeader reconstitute(PotId id, Label label, UserId creatorId, boolean deleted) {
		return new PotHeader(id, label, creatorId, deleted);
	}

	public void markAsDeleted() {
		assertNotDeleted();

		deleted = true;
	}

	public void updateDetails(Label label) {
		assertNotDeleted();

		this.label = Objects.requireNonNull(label, "label must not be null");
	}

	public PotId id() {
		return id;
	}

	public Label label() {
		return label;
	}

	public UserId creatorId() {
		return creatorId;
	}

	public boolean deleted() {
		return deleted;
	}

	private void assertNotDeleted() {
		if (deleted) {
			throw new BusinessRuleViolationException(
					"POT_DELETED",
					"Pot cannot be modified because it is deleted");
		}
	}
}
