package com.kartaguez.pocoma.domain.projection;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

public final class BalanceMapOperations {

	private BalanceMapOperations() {
	}

	public static Map<ShareholderId, Balance> add(
			Map<ShareholderId, Balance> left,
			Map<ShareholderId, Balance> right) {
		return combine(left, right, Operation.ADD);
	}

	public static Map<ShareholderId, Balance> subtract(
			Map<ShareholderId, Balance> left,
			Map<ShareholderId, Balance> right) {
		return combine(left, right, Operation.SUBTRACT);
	}

	private static Map<ShareholderId, Balance> combine(
			Map<ShareholderId, Balance> left,
			Map<ShareholderId, Balance> right,
			Operation operation) {
		Objects.requireNonNull(left, "left must not be null");
		Objects.requireNonNull(right, "right must not be null");
		Map<ShareholderId, Balance> result = new HashMap<>();

		left.forEach((shareholderId, balance) -> put(result, shareholderId, valueOf(shareholderId, balance)));
		right.forEach((shareholderId, balance) -> {
			Fraction rightValue = valueOf(shareholderId, balance);
			Fraction leftValue = result.getOrDefault(shareholderId, new Balance(shareholderId, Fraction.ZERO)).value();
			Fraction resultValue = operation.apply(leftValue, rightValue);
			put(result, shareholderId, resultValue);
		});

		return Map.copyOf(result);
	}

	private static Fraction valueOf(ShareholderId shareholderId, Balance balance) {
		Objects.requireNonNull(shareholderId, "balance shareholderId key must not be null");
		Objects.requireNonNull(balance, "balance must not be null");
		if (!shareholderId.equals(balance.shareholderId())) {
			throw new IllegalArgumentException("balance key must match balance shareholderId");
		}
		return balance.value();
	}

	private static void put(Map<ShareholderId, Balance> balances, ShareholderId shareholderId, Fraction value) {
		balances.put(shareholderId, new Balance(shareholderId, value));
	}

	private enum Operation {
		ADD {
			@Override
			Fraction apply(Fraction left, Fraction right) {
				return left.add(right);
			}
		},
		SUBTRACT {
			@Override
			Fraction apply(Fraction left, Fraction right) {
				return left.subtract(right);
			}
		};

		abstract Fraction apply(Fraction left, Fraction right);
	}
}
