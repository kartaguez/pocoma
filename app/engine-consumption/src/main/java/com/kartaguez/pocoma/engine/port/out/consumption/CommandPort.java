package com.kartaguez.pocoma.engine.port.out.consumption;

import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.ordering.CommandOrderingKey;
import com.kartaguez.pocoma.domain.consumption.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.context.consumption.ConsumableCommand;

/** Persistence operations for durable commands, without claim orchestration. */
public interface CommandPort {

	Optional<ConsumableCommand> findNextReady(
			WorkerSegment segment,
			Optional<CommandOrderingKey> afterExclusive);

	void markCompleted(UUID commandId);

	void markFailed(UUID commandId, ProcessingFailure failure);
}
