package com.kartaguez.pocoma.engine.port.out.consumption;

import java.util.List;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionInput;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionResult;

public interface ConsumptionProvenancePersistencePort {

	void appendInputs(List<ConsumptionInput> inputs);

	void appendResults(List<ConsumptionResult> results);

	List<ConsumptionInput> findInputs(UUID slotId);

	List<ConsumptionResult> findResults(UUID slotId);
}
