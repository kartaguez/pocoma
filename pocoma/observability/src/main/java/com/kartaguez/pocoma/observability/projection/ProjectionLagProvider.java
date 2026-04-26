package com.kartaguez.pocoma.observability.projection;

import java.util.List;

public interface ProjectionLagProvider {

	List<ProjectionVersionGap> loadProjectionVersionGaps();
}
