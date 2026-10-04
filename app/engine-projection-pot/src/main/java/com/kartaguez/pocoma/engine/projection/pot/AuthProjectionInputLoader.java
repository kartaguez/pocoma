package com.kartaguez.pocoma.engine.projection.pot;

import com.kartaguez.pocoma.projector.pot.AuthProjectionInput;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.engine.consume.projectiontask.engine.ProjectionInputLoader;

@FunctionalInterface
public interface AuthProjectionInputLoader extends ProjectionInputLoader<AuthProjectionInput> {
	@Override
	AuthProjectionInput load(ProjectionKey key);
}
