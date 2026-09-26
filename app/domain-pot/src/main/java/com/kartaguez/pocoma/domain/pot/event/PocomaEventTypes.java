package com.kartaguez.pocoma.domain.pot.event;

import java.util.Set;

import com.kartaguez.pocoma.domain.event.EventType;

/** Exhaustive catalogue of the canonical Pot business event types. */
public final class PocomaEventTypes {
	public static final EventType POT_CREATED = new EventType("POT_CREATED");
	public static final EventType POT_DELETED = new EventType("POT_DELETED");
	public static final EventType POT_DETAILS_UPDATED = new EventType("POT_DETAILS_UPDATED");
	public static final EventType POT_SHAREHOLDERS_ADDED = new EventType("POT_SHAREHOLDERS_ADDED");
	public static final EventType POT_SHAREHOLDERS_DETAILS_UPDATED =
			new EventType("POT_SHAREHOLDERS_DETAILS_UPDATED");
	public static final EventType POT_SHAREHOLDERS_WEIGHTS_UPDATED =
			new EventType("POT_SHAREHOLDERS_WEIGHTS_UPDATED");
	public static final EventType EXPENSE_CREATED = new EventType("EXPENSE_CREATED");
	public static final EventType EXPENSE_DELETED = new EventType("EXPENSE_DELETED");
	public static final EventType EXPENSE_DETAILS_UPDATED = new EventType("EXPENSE_DETAILS_UPDATED");
	public static final EventType EXPENSE_SHARES_UPDATED = new EventType("EXPENSE_SHARES_UPDATED");

	private static final Set<EventType> ALL = Set.of(
			POT_CREATED,
			POT_DELETED,
			POT_DETAILS_UPDATED,
			POT_SHAREHOLDERS_ADDED,
			POT_SHAREHOLDERS_DETAILS_UPDATED,
			POT_SHAREHOLDERS_WEIGHTS_UPDATED,
			EXPENSE_CREATED,
			EXPENSE_DELETED,
			EXPENSE_DETAILS_UPDATED,
			EXPENSE_SHARES_UPDATED);

	private PocomaEventTypes() {}

	public static Set<EventType> all() {
		return ALL;
	}
}
