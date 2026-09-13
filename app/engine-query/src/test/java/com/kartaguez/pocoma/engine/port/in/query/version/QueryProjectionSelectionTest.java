package com.kartaguez.pocoma.engine.port.in.query.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pipeline.PipelineVersionDefinition;
import com.kartaguez.pocoma.domain.pipeline.VersionApplicability;
import com.kartaguez.pocoma.domain.projection.ProjectionType;

class QueryProjectionSelectionTest {

	private static final ProjectionType READ_POT = new ProjectionType("READ_POT");

	@Test
	void preservesTheSingleProjectionAndItsAuthoritativeServingPipeline() {
		PipelineVersionDefinition serving = pipeline("read-pot", 3);

		QueryProjectionSelection selection = new QueryProjectionSelection(READ_POT, serving);

		assertSame(READ_POT, selection.projectionType());
		assertSame(serving, selection.servingPipeline());
		assertEquals(new QueryProjectionSelection(READ_POT, serving), selection);
	}

	@Test
	void rejectsNullProjectionOrServingPipeline() {
		PipelineVersionDefinition serving = pipeline("read-pot", 1);

		assertThrows(NullPointerException.class, () -> new QueryProjectionSelection(null, serving));
		assertThrows(NullPointerException.class, () -> new QueryProjectionSelection(READ_POT, null));
	}

	private static PipelineVersionDefinition pipeline(String id, int version) {
		return new PipelineVersionDefinition(
				new PipelineDefinition(new PipelineId(id), version),
				VersionApplicability.from(1));
	}
}
