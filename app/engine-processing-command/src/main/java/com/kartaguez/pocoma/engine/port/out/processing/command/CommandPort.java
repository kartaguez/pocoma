package com.kartaguez.pocoma.engine.port.out.processing.command;

import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.engine.processing.command.ordering.CommandOrderingKey;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.port.out.processing.command.model.RecordedCommand;

/** Persistence boundary for selecting and changing durable commands. */
public interface CommandPort {

	Optional<RecordedCommand> findNextReady(
			WorkerSegment segment,
			Optional<CommandOrderingKey> afterExclusive);

	void markCompleted(UUID commandId);

	void markFailed(UUID commandId, ProcessingFailure failure);
}
