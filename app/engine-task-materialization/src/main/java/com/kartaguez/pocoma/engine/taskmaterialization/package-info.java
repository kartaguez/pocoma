/**
 * Transitional implementation of event-to-task creation.
 *
 * <p>The target responsibility belongs to the {@code engine-task-creation} module: given an
 * immutable event and a pipeline definition, determine and create zero to many autonomous tasks.
 * Event-consumption ownership and status belong to {@code engine-consumption}, not to this
 * functional use case. Polling, claims, leases, retries, and worker lifecycle are excluded.</p>
 */
package com.kartaguez.pocoma.engine.taskmaterialization;
