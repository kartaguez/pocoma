package com.kartaguez.pocoma.pipeline.balance.projection;

import com.kartaguez.pocoma.engine.taskexecution.model.NonRetryableTaskTechnicalFailure;

public final class BalanceProjectionConflictException extends RuntimeException
		implements NonRetryableTaskTechnicalFailure {
	public static final String CATEGORY = "BALANCE_PROJECTION_CONFLICT";
	public BalanceProjectionConflictException() {
		super("An immutable Balance projection already exists with different content");
	}
	@Override public String failureCategory() { return CATEGORY; }
}
