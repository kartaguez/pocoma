/**
 * In-process segmented execution pool.
 *
 * <p>The pool owns bounded queues and segment threads. A stable segment key routes
 * all work for the same entity to the same local segment, preserving order for that
 * key while allowing other keys to run in parallel.
 */
package com.kartaguez.pocoma.orchestrator.claimable.pool;
