package com.kartaguez.pocoma.engine.port.in.execution.usecase;

import com.kartaguez.pocoma.engine.port.in.execution.result.ExecutionOutcome;

/** Prevents the effective execution identified by a technical key from being committed twice. */
@FunctionalInterface
public interface ExecutionGuard<K> {

	ExecutionOutcome executeOnce(K key, Runnable execution);
}
