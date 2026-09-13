package com.kartaguez.pocoma.engine.port.in.query.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pipeline.PipelineVersionDefinition;
import com.kartaguez.pocoma.domain.pipeline.VersionApplicability;
import com.kartaguez.pocoma.domain.projection.ProjectionType;

class QueryPipelineSelectionTest {

	private static final ProjectionType READ_POT = new ProjectionType("READ_POT");
	private static final ProjectionType BALANCE = new ProjectionType("BALANCE");

	@Test
	void preservesExplicitPipelineVersionSelectionByProjectionType() {
		PipelineVersionDefinition selected = pipeline("read-pot", 3);
		QueryPipelineSelection selection = new QueryPipelineSelection(Map.of(READ_POT, selected));

		assertSame(selected, selection.requireFor(READ_POT));
		assertEquals(Map.of(READ_POT, selected), selection.pipelinesByComponent());
	}

	@Test
	void acceptsEmptySelectionWhenViewRequiresNoComponent() {
		QueryViewDefinition view = QueryViewDefinition.unprotectedView(java.util.Set.of());
		QueryPipelineSelection selection = new QueryPipelineSelection(Map.of());

		assertEquals(java.util.Set.of(), view.requiredComponents());
		assertEquals(Map.of(), selection.pipelinesByComponent());
	}

	@Test
	void failsExplicitlyForMissingOrNullComponent() {
		QueryPipelineSelection selection = new QueryPipelineSelection(Map.of(READ_POT, pipeline("read-pot", 1)));

		assertThrows(IllegalArgumentException.class, () -> selection.requireFor(BALANCE));
		assertThrows(NullPointerException.class, () -> selection.requireFor(null));
	}

	@Test
	void rejectsNullMapKeysAndValues() {
		assertThrows(NullPointerException.class, () -> new QueryPipelineSelection(null));

		Map<ProjectionType, PipelineVersionDefinition> nullKey = new HashMap<>();
		nullKey.put(null, pipeline("read-pot", 1));
		assertThrows(NullPointerException.class, () -> new QueryPipelineSelection(nullKey));

		Map<ProjectionType, PipelineVersionDefinition> nullValue = new HashMap<>();
		nullValue.put(READ_POT, null);
		assertThrows(NullPointerException.class, () -> new QueryPipelineSelection(nullValue));
	}

	@Test
	void defensivelyCopiesAndExposesImmutableSelection() {
		PipelineVersionDefinition selected = pipeline("read-pot", 2);
		Map<ProjectionType, PipelineVersionDefinition> source = new HashMap<>();
		source.put(READ_POT, selected);
		QueryPipelineSelection selection = new QueryPipelineSelection(source);

		source.put(BALANCE, pipeline("balance", 99));

		assertEquals(Map.of(READ_POT, selected), selection.pipelinesByComponent());
		assertThrows(UnsupportedOperationException.class,
				() -> selection.pipelinesByComponent().put(BALANCE, pipeline("balance", 1)));
	}

	private static PipelineVersionDefinition pipeline(String id, int version) {
		return new PipelineVersionDefinition(
				new PipelineDefinition(new PipelineId(id), version),
				VersionApplicability.from(1));
	}
}
