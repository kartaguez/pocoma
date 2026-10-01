package com.kartaguez.pocoma.engine.service.command;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.kartaguez.pocoma.domain.event.BusinessEvent;
import com.kartaguez.pocoma.domain.pot.event.ExpenseCreatedEvent;
import com.kartaguez.pocoma.domain.pot.event.ExpenseDeletedEvent;
import com.kartaguez.pocoma.domain.pot.event.ExpenseDetailsUpdatedEvent;
import com.kartaguez.pocoma.domain.pot.event.ExpenseSharesUpdatedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotDeletedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotDetailsUpdatedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotShareholdersAddedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotShareholdersDetailsUpdatedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotShareholdersWeightsUpdatedEvent;
import com.kartaguez.pocoma.engine.command.model.CommandExecutionInput;
import com.kartaguez.pocoma.engine.context.AddPotShareholdersContext;
import com.kartaguez.pocoma.engine.context.CreateExpenseContext;
import com.kartaguez.pocoma.engine.context.DeleteExpenseContext;
import com.kartaguez.pocoma.engine.context.DeletePotContext;
import com.kartaguez.pocoma.engine.context.UpdateExpenseDetailsContext;
import com.kartaguez.pocoma.engine.context.UpdateExpenseSharesContext;
import com.kartaguez.pocoma.engine.context.UpdatePotDetailsContext;
import com.kartaguez.pocoma.engine.context.UpdatePotShareholdersDetailsContext;
import com.kartaguez.pocoma.engine.context.UpdatePotShareholdersWeightsContext;
import com.kartaguez.pocoma.engine.pot.version.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotContextPort;

/** Invocation-local capture of typed Events and business versions read by a legacy service. */
final class PotCommandInvocation implements EventPublisherPort {

	private final Set<CommandExecutionInput> inputs = new LinkedHashSet<>();
	private final List<BusinessEvent> events = new ArrayList<>();

	List<CommandExecutionInput> inputs() {
		return List.copyOf(inputs);
	}

	List<BusinessEvent> events() {
		return List.copyOf(events);
	}

	PotContextPort recording(PotContextPort delegate) {
		return new PotContextPort() {
			@Override
			public AddPotShareholdersContext loadAddPotShareholdersContext(
					com.kartaguez.pocoma.domain.pot.value.id.PotId potId) {
				return capture(delegate.loadAddPotShareholdersContext(potId));
			}

			@Override
			public CreateExpenseContext loadCreateExpenseContext(
					com.kartaguez.pocoma.domain.pot.value.id.PotId potId) {
				return capture(delegate.loadCreateExpenseContext(potId));
			}

			@Override
			public DeletePotContext loadDeletePotContext(
					com.kartaguez.pocoma.domain.pot.value.id.PotId potId) {
				return capture(delegate.loadDeletePotContext(potId));
			}

			@Override
			public UpdatePotDetailsContext loadUpdatePotDetailsContext(
					com.kartaguez.pocoma.domain.pot.value.id.PotId potId) {
				return capture(delegate.loadUpdatePotDetailsContext(potId));
			}

			@Override
			public UpdatePotShareholdersDetailsContext loadUpdatePotShareholdersDetailsContext(
					com.kartaguez.pocoma.domain.pot.value.id.PotId potId) {
				return capture(delegate.loadUpdatePotShareholdersDetailsContext(potId));
			}

			@Override
			public UpdatePotShareholdersWeightsContext loadUpdatePotShareholdersWeightsContext(
					com.kartaguez.pocoma.domain.pot.value.id.PotId potId) {
				return capture(delegate.loadUpdatePotShareholdersWeightsContext(potId));
			}
		};
	}

	ExpenseContextPort recording(ExpenseContextPort delegate) {
		return new ExpenseContextPort() {
			@Override
			public DeleteExpenseContext loadDeleteExpenseContext(
					com.kartaguez.pocoma.domain.pot.value.id.ExpenseId expenseId) {
				return capture(delegate.loadDeleteExpenseContext(expenseId));
			}

			@Override
			public UpdateExpenseDetailsContext loadUpdateExpenseDetailsContext(
					com.kartaguez.pocoma.domain.pot.value.id.ExpenseId expenseId) {
				return capture(delegate.loadUpdateExpenseDetailsContext(expenseId));
			}

			@Override
			public UpdateExpenseSharesContext loadUpdateExpenseSharesContext(
					com.kartaguez.pocoma.domain.pot.value.id.ExpenseId expenseId) {
				return capture(delegate.loadUpdateExpenseSharesContext(expenseId));
			}
		};
	}

	@Override public void publish(ExpenseCreatedEvent event) { events.add(event); }
	@Override public void publish(ExpenseDeletedEvent event) { events.add(event); }
	@Override public void publish(ExpenseDetailsUpdatedEvent event) { events.add(event); }
	@Override public void publish(ExpenseSharesUpdatedEvent event) { events.add(event); }
	@Override public void publish(PotCreatedEvent event) { events.add(event); }
	@Override public void publish(PotDeletedEvent event) { events.add(event); }
	@Override public void publish(PotDetailsUpdatedEvent event) { events.add(event); }
	@Override public void publish(PotShareholdersAddedEvent event) { events.add(event); }
	@Override public void publish(PotShareholdersDetailsUpdatedEvent event) { events.add(event); }
	@Override public void publish(PotShareholdersWeightsUpdatedEvent event) { events.add(event); }

	private <T> T capture(T context) {
		if (context instanceof AddPotShareholdersContext value) record(value.potGlobalVersion());
		else if (context instanceof CreateExpenseContext value) record(value.potGlobalVersion());
		else if (context instanceof DeletePotContext value) record(value.potGlobalVersion());
		else if (context instanceof UpdatePotDetailsContext value) record(value.potGlobalVersion());
		else if (context instanceof UpdatePotShareholdersDetailsContext value) record(value.potGlobalVersion());
		else if (context instanceof UpdatePotShareholdersWeightsContext value) record(value.potGlobalVersion());
		else if (context instanceof DeleteExpenseContext value) record(value.potGlobalVersion());
		else if (context instanceof UpdateExpenseDetailsContext value) record(value.potGlobalVersion());
		else if (context instanceof UpdateExpenseSharesContext value) record(value.potGlobalVersion());
		return context;
	}

	private void record(PotGlobalVersion version) {
		inputs.add(new CommandExecutionInput("POT", version.potId().value().toString(), version.version()));
	}
}
