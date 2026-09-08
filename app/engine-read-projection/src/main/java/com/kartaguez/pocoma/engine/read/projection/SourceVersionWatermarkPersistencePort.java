package com.kartaguez.pocoma.engine.read.projection;

public interface SourceVersionWatermarkPersistencePort {
	SourceVersionObservation observe(ObserveSourceVersionInput input);
}
