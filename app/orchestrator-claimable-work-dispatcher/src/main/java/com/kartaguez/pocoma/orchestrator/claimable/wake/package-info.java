/**
 * Best-effort wake signals.
 *
 * <p>Wake signals only shorten latency between polling ticks. They are never a work
 * transport and never a source of truth; the dispatcher always claims durable work
 * from its {@code ClaimableWorkSource}.
 */
package com.kartaguez.pocoma.orchestrator.claimable.wake;
