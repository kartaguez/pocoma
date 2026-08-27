package com.kartaguez.pocoma.domain.consumption.ordering;

import java.util.UUID;

final class OrderingComparisons {

	private OrderingComparisons() {
	}

	static int compareUuid(UUID left, UUID right) {
		return left.toString().compareTo(right.toString());
	}
}
