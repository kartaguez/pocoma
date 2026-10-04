package com.kartaguez.pocoma.engine.projection.task;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;

/** A requested projection. Its complete semantic identity is its ProjectionKey. */
public record ProjectionTask(ProjectionKey projectionKey) {
	public ProjectionTask {
		requireNonNull(projectionKey, "projectionKey must not be null");
	}
}
