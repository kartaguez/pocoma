/**
 * Contracts implemented by business-specific durable work sources and handlers.
 *
 * <p>Application adapters implement {@link com.kartaguez.pocoma.orchestrator.claimable.work.ClaimableWorkLifecycle}
 * to connect the generic dispatcher to a database table. Use cases are exposed as
 * {@link com.kartaguez.pocoma.orchestrator.claimable.work.WorkHandler}
 * implementations.
 */
package com.kartaguez.pocoma.orchestrator.claimable.work;
