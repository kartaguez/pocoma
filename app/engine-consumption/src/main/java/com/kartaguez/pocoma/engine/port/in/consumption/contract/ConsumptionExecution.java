package com.kartaguez.pocoma.engine.port.in.consumption.contract;

import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;

/**
 * Executes only rollbackable work in the primary transaction. External effects must
 * be represented by a durable Task or outbox record.
 */
@FunctionalInterface
public interface ConsumptionExecution {

	ConsumptionExecutionResult execute(ConsumptionExecutionContext context);
}
