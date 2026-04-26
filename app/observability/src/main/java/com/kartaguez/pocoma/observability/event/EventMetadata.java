package com.kartaguez.pocoma.observability.event;

import java.util.Objects;

import com.kartaguez.pocoma.engine.event.ExpenseCreatedEvent;
import com.kartaguez.pocoma.engine.event.ExpenseDeletedEvent;
import com.kartaguez.pocoma.engine.event.ExpenseDetailsUpdatedEvent;
import com.kartaguez.pocoma.engine.event.ExpenseSharesUpdatedEvent;
import com.kartaguez.pocoma.engine.event.PotCreatedEvent;
import com.kartaguez.pocoma.engine.event.PotDeletedEvent;
import com.kartaguez.pocoma.engine.event.PotDetailsUpdatedEvent;
import com.kartaguez.pocoma.engine.event.PotShareholdersAddedEvent;
import com.kartaguez.pocoma.engine.event.PotShareholdersDetailsUpdatedEvent;
import com.kartaguez.pocoma.engine.event.PotShareholdersWeightsUpdatedEvent;

public final class EventMetadata {

	private EventMetadata() {
	}

	public static String type(Object event) {
		return Objects.requireNonNull(event, "event must not be null").getClass().getSimpleName();
	}

	public static String operation(Object event) {
		return switch (event) {
			case PotCreatedEvent ignored -> "createPot";
			case PotDeletedEvent ignored -> "deletePot";
			case PotDetailsUpdatedEvent ignored -> "updatePotDetails";
			case PotShareholdersAddedEvent ignored -> "addPotShareholders";
			case PotShareholdersDetailsUpdatedEvent ignored -> "updatePotShareholdersDetails";
			case PotShareholdersWeightsUpdatedEvent ignored -> "updatePotShareholdersWeights";
			case ExpenseCreatedEvent ignored -> "createExpense";
			case ExpenseDeletedEvent ignored -> "deleteExpense";
			case ExpenseDetailsUpdatedEvent ignored -> "updateExpenseDetails";
			case ExpenseSharesUpdatedEvent ignored -> "updateExpenseShares";
			default -> type(event);
		};
	}
}
