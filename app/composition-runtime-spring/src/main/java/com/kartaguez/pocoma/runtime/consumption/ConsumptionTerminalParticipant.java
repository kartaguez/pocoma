package com.kartaguez.pocoma.runtime.consumption;

import java.time.Clock;
import com.kartaguez.pocoma.engine.service.consumption.FinalizeConsumptionService;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionLifecyclePersistencePort;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionProvenancePersistencePort;

/** Internal composition holder, deliberately not a public FinalizeConsumptionUseCase bean. */
public record ConsumptionTerminalParticipant(FinalizeConsumptionService finalizer) {
	public ConsumptionTerminalParticipant(ConsumptionLifecyclePersistencePort lifecycle,
			ConsumptionProvenancePersistencePort provenance, Clock clock) {
		this(new FinalizeConsumptionService(lifecycle, provenance, clock));
	}
}
