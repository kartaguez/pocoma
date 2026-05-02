/**
 * Orchestration entry points for claimable durable work.
 *
 * <p>The dispatcher owns the polling cycle: wait for a wake signal or timeout, check
 * local capacity, claim a bounded batch, admit each claimed work item, and release it
 * if the local pool refuses admission.
 */
package com.kartaguez.pocoma.orchestrator.claimable.dispatcher;
