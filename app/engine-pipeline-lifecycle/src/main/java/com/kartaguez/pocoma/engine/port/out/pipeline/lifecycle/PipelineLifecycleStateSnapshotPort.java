package com.kartaguez.pocoma.engine.port.out.pipeline.lifecycle;

import com.kartaguez.pocoma.engine.pipeline.lifecycle.model.PipelineLifecycleStateSnapshot;

public interface PipelineLifecycleStateSnapshotPort {
	PipelineLifecycleStateSnapshot loadSnapshot();
}
