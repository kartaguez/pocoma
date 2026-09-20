package com.kartaguez.pocoma.engine.projection.pot;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;

@FunctionalInterface
public interface ReadPotProjectionInputLoader {
	ReadPotProjectionInput load(ProjectionKey key);
}
