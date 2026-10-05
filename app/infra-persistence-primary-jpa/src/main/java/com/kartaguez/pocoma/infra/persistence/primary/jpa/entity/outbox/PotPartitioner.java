package com.kartaguez.pocoma.infra.persistence.primary.jpa.entity.outbox;

import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public final class PotPartitioner {

	private PotPartitioner() {
	}

	public static int partitionHash(PotId potId) {
		Objects.requireNonNull(potId, "potId must not be null");
		return partitionHash(potId.value());
	}

	public static int partitionHash(UUID potId) {
		Objects.requireNonNull(potId, "potId must not be null");
		return potId.hashCode() & Integer.MAX_VALUE;
	}

	public static int segmentOf(PotId potId, int segmentCount) {
		return segmentOf(partitionHash(potId), segmentCount);
	}

	public static int segmentOf(int partitionHash, int segmentCount) {
		if (segmentCount < 1) {
			throw new IllegalArgumentException("segmentCount must be greater than or equal to 1");
		}
		return Math.floorMod(partitionHash, segmentCount);
	}

}
