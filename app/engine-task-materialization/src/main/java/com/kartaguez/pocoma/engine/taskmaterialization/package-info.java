/**
 * Transitional implementation of event-to-task creation.
 *
 * <p>The target responsibility belongs to the {@code engine-task-creation} module: given an
 * immutable event and a pipeline definition, determine and create zero to many autonomous tasks.
 * Event-consumption ownership and status belong to {@code engine-consumption}, not to this
 * functional use case. This package remains only for the current materialization worker and JPA
 * bridge. It will be removed after that worker delegates to {@code engine-task-creation} using an
 * independent consumption key {@code (pipelineId, pipelineVersion, eventId)}.</p>
 */
package com.kartaguez.pocoma.engine.taskmaterialization;
