package com.kartaguez.pocoma.engine.taskexecution.model;

/** Marker for a technical Task failure that must be terminal immediately. */
public interface NonRetryableTaskTechnicalFailure {
	String failureCategory();
}
