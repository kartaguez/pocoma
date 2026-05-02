package com.kartaguez.pocoma.engine.port.out.event;

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

public interface EventPublisherPort {

	default void publish(ExpenseCreatedEvent event) {
		throw new UnsupportedOperationException("ExpenseCreatedEvent publishing is not implemented");
	}

	default void publish(ExpenseDeletedEvent event) {
		throw new UnsupportedOperationException("ExpenseDeletedEvent publishing is not implemented");
	}

	default void publish(ExpenseDetailsUpdatedEvent event) {
		throw new UnsupportedOperationException("ExpenseDetailsUpdatedEvent publishing is not implemented");
	}

	default void publish(ExpenseSharesUpdatedEvent event) {
		throw new UnsupportedOperationException("ExpenseSharesUpdatedEvent publishing is not implemented");
	}

	default void publish(PotCreatedEvent event) {
		throw new UnsupportedOperationException("PotCreatedEvent publishing is not implemented");
	}

	default void publish(PotDeletedEvent event) {
		throw new UnsupportedOperationException("PotDeletedEvent publishing is not implemented");
	}

	default void publish(PotDetailsUpdatedEvent event) {
		throw new UnsupportedOperationException("PotDetailsUpdatedEvent publishing is not implemented");
	}

	default void publish(PotShareholdersAddedEvent event) {
		throw new UnsupportedOperationException("PotShareholdersAddedEvent publishing is not implemented");
	}

	default void publish(PotShareholdersDetailsUpdatedEvent event) {
		throw new UnsupportedOperationException("PotShareholdersDetailsUpdatedEvent publishing is not implemented");
	}

	default void publish(PotShareholdersWeightsUpdatedEvent event) {
		throw new UnsupportedOperationException("PotShareholdersWeightsUpdatedEvent publishing is not implemented");
	}
}
