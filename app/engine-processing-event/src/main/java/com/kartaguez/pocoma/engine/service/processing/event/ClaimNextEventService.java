package com.kartaguez.pocoma.engine.service.processing.event;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.port.in.consumption.input.TryAcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult.Acquired;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.TryAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.event.input.ClaimNextEventInput;
import com.kartaguez.pocoma.engine.port.in.processing.event.result.EventClaimResult;
import com.kartaguez.pocoma.engine.port.in.processing.event.usecase.ClaimNextEventUseCase;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventPort;
import com.kartaguez.pocoma.engine.processing.event.ordering.EventOrderingKey;

public final class ClaimNextEventService implements ClaimNextEventUseCase {

	private final EventPort eventPort;
	private final TryAcquireConsumptionUseCase tryAcquireConsumptionUseCase;

	public ClaimNextEventService(
			EventPort eventPort,
			TryAcquireConsumptionUseCase tryAcquireConsumptionUseCase) {
		this.eventPort = requireNonNull(eventPort, "eventPort must not be null");
		this.tryAcquireConsumptionUseCase = requireNonNull(
				tryAcquireConsumptionUseCase, "tryAcquireConsumptionUseCase must not be null");
	}

	@Override
	public Optional<EventClaimResult> claimNext(ClaimNextEventInput input) {
		requireNonNull(input, "input must not be null");
		Optional<EventOrderingKey> cursor = Optional.empty();
		while (true) {
			Optional<RecordedEvent<? extends BusinessEvent>> candidate = eventPort.findNextCandidate(
					input.pipeline(), input.segment(), cursor);
			if (candidate.isEmpty()) {
				return Optional.empty();
			}
			RecordedEvent<? extends BusinessEvent> event = candidate.orElseThrow();
			TryAcquireConsumptionResult acquisition = tryAcquireConsumptionUseCase.tryAcquire(
					new TryAcquireConsumptionInput(
							EventProcessingKeys.forEvent(input.pipeline(), event.eventId()),
							input.workerId(),
							input.lease()));
			if (acquisition instanceof Acquired acquired) {
				return Optional.of(new EventClaimResult(input.pipeline(), event, acquired.claim()));
			}
			cursor = Optional.of(new EventOrderingKey(
					event.event().version(), event.recordedAt(), event.eventId()));
		}
	}
}
