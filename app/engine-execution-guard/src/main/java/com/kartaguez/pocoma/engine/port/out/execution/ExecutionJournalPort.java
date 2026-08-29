package com.kartaguez.pocoma.engine.port.out.execution;

import java.time.Instant;

/** Atomic insert-if-absent boundary for an execution journal. */
@FunctionalInterface
public interface ExecutionJournalPort<K> {

	boolean tryRegister(K key, Instant executedAt);
}
