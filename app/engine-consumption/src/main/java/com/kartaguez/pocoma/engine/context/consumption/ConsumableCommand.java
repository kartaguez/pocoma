package com.kartaguez.pocoma.engine.context.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.key.CommandConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionStatus;
import com.kartaguez.pocoma.domain.consumption.ordering.CommandOrderingKey;
import com.kartaguez.pocoma.domain.consumption.segmentation.PartitionHash;
import com.kartaguez.pocoma.domain.consumption.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.port.in.command.intent.CommandIntent;

/** Durable application representation of a typed business command. */
public record ConsumableCommand(
		UUID commandId,
		Optional<UUID> potId,
		Instant createdAt,
		CommandIntent intent,
		ConsumptionStatus status) {

	public ConsumableCommand {
		requireNonNull(commandId, "commandId must not be null");
		potId = requireNonNull(potId, "potId must not be null");
		requireNonNull(createdAt, "createdAt must not be null");
		requireNonNull(intent, "intent must not be null");
		requireNonNull(status, "status must not be null");
	}

	public CommandConsumptionKey consumptionKey() {
		return new CommandConsumptionKey(commandId);
	}

	public CommandOrderingKey orderingKey() {
		return new CommandOrderingKey(createdAt, commandId);
	}

	public boolean isEligibleFor(WorkerSegment segment) {
		requireNonNull(segment, "segment must not be null");
		return status == ConsumptionStatus.READY
				&& potId.map(id -> segment.owns(PartitionHash.forPot(id))).orElse(true);
	}
}
