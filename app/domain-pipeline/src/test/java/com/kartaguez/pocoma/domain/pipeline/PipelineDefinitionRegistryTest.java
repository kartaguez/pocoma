package com.kartaguez.pocoma.domain.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PipelineDefinitionRegistryTest {
	private static final PipelineId ID = PipelineId.of("history");

	@Test
	void applicabilitySupportsClosedAndOpenRanges() {
		var closed = VersionApplicability.between(37, 100);
		assertFalse(closed.appliesTo(36));
		assertTrue(closed.appliesTo(37));
		assertTrue(closed.appliesTo(100));
		assertFalse(closed.appliesTo(101));
		assertTrue(VersionApplicability.from(37).appliesTo(Long.MAX_VALUE));
		assertThrows(IllegalArgumentException.class, () -> VersionApplicability.from(0));
		assertThrows(IllegalArgumentException.class, () -> VersionApplicability.between(2, 1));
	}

	@Test
	void registriesBuiltFromCanonicalCatalogHaveEqualDefinitions() {
		var first = new PipelineDefinitionRegistry(PocomaPipelineDefinitions.all());
		var copiedCatalog = PocomaPipelineDefinitions.all().stream()
				.map(item -> new PipelineVersionDefinition(item.identity(), item.applicability()))
				.toList();
		var second = new PipelineDefinitionRegistry(copiedCatalog);
		var identity = new PipelineDefinition(PipelineId.of("balance-projection"), 2);
		assertEquals(first.require(identity), second.require(identity));
		assertNotSame(first.require(identity), second.require(identity));
		assertThrows(UnsupportedOperationException.class,
				() -> PocomaPipelineDefinitions.all().add(definition(9, 1)));
	}

	@Test
	void exactLookupRetainsHistoricalVersionsAndNeverSelectsHighestVersion() {
		var v1 = definition(1, 1);
		var v2 = definition(2, 37);
		var registry = new PipelineDefinitionRegistry(List.of(v1, v2));
		assertEquals(v1, registry.require(v1.identity()));
		assertEquals(v2, registry.require(v2.identity()));
		assertThrows(UnknownPipelineDefinitionException.class,
				() -> registry.require(new PipelineDefinition(ID, 3)));
	}

	@Test
	void everyDuplicateIdentityIsRejected() {
		var definition = definition(1, 1);
		assertThrows(IllegalArgumentException.class,
				() -> new PipelineDefinitionRegistry(List.of(definition, definition)));
		assertThrows(IllegalArgumentException.class, () -> new PipelineDefinitionRegistry(List.of(
				definition, new PipelineVersionDefinition(definition.identity(), VersionApplicability.from(2)))));
	}

	private static PipelineVersionDefinition definition(int version, long from) {
		return new PipelineVersionDefinition(new PipelineDefinition(ID, version), VersionApplicability.from(from));
	}
}
