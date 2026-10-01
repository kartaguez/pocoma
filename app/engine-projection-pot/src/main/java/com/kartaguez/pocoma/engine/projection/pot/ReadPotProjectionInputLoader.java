package com.kartaguez.pocoma.engine.projection.pot;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.engine.projection.task.engine.ProjectionInputLoader;

@FunctionalInterface

public interface ReadPotProjectionInputLoader extends ProjectionInputLoader<ReadPotProjectionInput> {
	@Override
	ReadPotProjectionInput load(ProjectionKey key);
}
