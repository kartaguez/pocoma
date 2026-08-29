package com.kartaguez.pocoma.engine.service.execution;

import static java.util.Objects.requireNonNull;

import java.time.Clock;

import com.kartaguez.pocoma.engine.port.in.execution.result.ExecutionOutcome;
import com.kartaguez.pocoma.engine.port.in.execution.usecase.ExecutionGuard;
import com.kartaguez.pocoma.engine.port.out.execution.ExecutionJournalPort;

/**
 * Registers before invoking the effect. Production wiring must wrap this service in
 * {@code TransactionalExecutionGuard} so a callback failure rolls both operations back.
 */
public final class ExecutionGuardService<K> implements ExecutionGuard<K> {

	private final ExecutionJournalPort<K> executionJournalPort;
	private final Clock clock;

	public ExecutionGuardService(ExecutionJournalPort<K> executionJournalPort, Clock clock) {
		this.executionJournalPort = requireNonNull(executionJournalPort, "executionJournalPort must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	public ExecutionOutcome executeOnce(K key, Runnable execution) {
		requireNonNull(key, "key must not be null");
		requireNonNull(execution, "execution must not be null");
		if (!executionJournalPort.tryRegister(key, clock.instant())) {
			return ExecutionOutcome.ALREADY_EXECUTED;
		}
		execution.run();
		return ExecutionOutcome.EXECUTED;
	}
}
