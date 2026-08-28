package com.kartaguez.pocoma.engine.service.transaction.taskcreation;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationResult;
import com.kartaguez.pocoma.engine.port.in.taskcreation.input.CreateTasksForEventInput;
import com.kartaguez.pocoma.engine.port.in.taskcreation.usecase.CreateTasksForEventUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalCreateTasksForEventUseCase implements CreateTasksForEventUseCase {

	private final CreateTasksForEventUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalCreateTasksForEventUseCase(
			CreateTasksForEventUseCase delegate,
			TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public TaskCreationResult createTasks(CreateTasksForEventInput input) {
		return transactionRunner.runInTransaction(() -> delegate.createTasks(input));
	}
}
