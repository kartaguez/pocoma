package com.kartaguez.pocoma.engine.consume.projectiontask.input;

import com.kartaguez.pocoma.projector.pot.ReadPotProjectionInput;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.engine.consume.projectiontask.engine.ProjectionInputLoader;

@FunctionalInterface

public interface ReadPotProjectionInputLoader extends ProjectionInputLoader<ReadPotProjectionInput> {
	@Override
	ReadPotProjectionInput load(ProjectionKey key);
}
