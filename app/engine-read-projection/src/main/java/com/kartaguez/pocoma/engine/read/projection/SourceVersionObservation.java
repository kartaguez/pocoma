package com.kartaguez.pocoma.engine.read.projection;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.projection.SourceVersionWatermark;

public sealed interface SourceVersionObservation {

	SourceVersionWatermark watermark();

	record Advanced(SourceVersionWatermark watermark) implements SourceVersionObservation {
		public Advanced { requireNonNull(watermark, "watermark must not be null"); }
	}

	record Unchanged(SourceVersionWatermark watermark) implements SourceVersionObservation {
		public Unchanged { requireNonNull(watermark, "watermark must not be null"); }
	}
}
