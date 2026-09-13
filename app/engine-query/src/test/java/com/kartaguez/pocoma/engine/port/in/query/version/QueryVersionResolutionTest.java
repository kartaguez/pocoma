package com.kartaguez.pocoma.engine.port.in.query.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.OptionalLong;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;

class QueryVersionResolutionTest {

	private static final PotId POT_ID = PotId.of(UUID.randomUUID());

	@Test
	void resolvedPreservesIntentAndValidatesExposureBounds() {
		QueryVersionIntent current = QueryVersionIntent.current();
		QueryVersionResolution.Resolved resolved = new QueryVersionResolution.Resolved(POT_ID, current, 13, 15);

		assertEquals(POT_ID, resolved.potId());
		assertEquals(current, resolved.requestedVersion());
		assertEquals(13, resolved.servedVersion());
		assertEquals(15, resolved.latestKnownVersion());
		assertThrows(IllegalArgumentException.class,
				() -> new QueryVersionResolution.Resolved(POT_ID, current, 0, 15));
		assertThrows(IllegalArgumentException.class,
				() -> new QueryVersionResolution.Resolved(POT_ID, current, 13, 0));
		assertThrows(IllegalArgumentException.class,
				() -> new QueryVersionResolution.Resolved(POT_ID, current, 16, 15));
	}

	@Test
	void resolvedExactMustMatchRequestedBusinessVersion() {
		assertEquals(
				new QueryVersionResolution.Resolved(POT_ID, QueryVersionIntent.exact(14), 14, 15),
				new QueryVersionResolution.Resolved(POT_ID, QueryVersionIntent.exact(14), 14, 15));
		assertThrows(IllegalArgumentException.class,
				() -> new QueryVersionResolution.Resolved(POT_ID, QueryVersionIntent.exact(14), 13, 15));
	}

	@Test
	void projectionFailedPreservesIntentAndValidatesExposureBounds() {
		QueryVersionIntent current = QueryVersionIntent.current();
		QueryVersionResolution.ProjectionFailed failed =
				new QueryVersionResolution.ProjectionFailed(POT_ID, current, 14, 15);

		assertEquals(14, failed.failedVersion());
		assertEquals(15, failed.latestKnownVersion());
		assertThrows(IllegalArgumentException.class,
				() -> new QueryVersionResolution.ProjectionFailed(POT_ID, current, 0, 15));
		assertThrows(IllegalArgumentException.class,
				() -> new QueryVersionResolution.ProjectionFailed(POT_ID, current, 14, 0));
		assertThrows(IllegalArgumentException.class,
				() -> new QueryVersionResolution.ProjectionFailed(POT_ID, current, 16, 15));
	}

	@Test
	void projectionFailedExactMustMatchRequestedBusinessVersion() {
		new QueryVersionResolution.ProjectionFailed(POT_ID, QueryVersionIntent.exact(14), 14, 15);
		assertThrows(IllegalArgumentException.class,
				() -> new QueryVersionResolution.ProjectionFailed(
						POT_ID, QueryVersionIntent.exact(14), 13, 15));
	}

	@Test
	void notReadyAloneCanRepresentAbsentLatestKnown() {
		QueryVersionResolution.NotReady absent = new QueryVersionResolution.NotReady(
				POT_ID, QueryVersionIntent.current(), OptionalLong.empty());
		QueryVersionResolution.NotReady present = new QueryVersionResolution.NotReady(
				POT_ID, QueryVersionIntent.exact(12), OptionalLong.of(15));

		assertEquals(OptionalLong.empty(), absent.latestKnownVersion());
		assertEquals(OptionalLong.of(15), present.latestKnownVersion());
		assertThrows(NullPointerException.class,
				() -> new QueryVersionResolution.NotReady(POT_ID, QueryVersionIntent.current(), null));
		assertThrows(IllegalArgumentException.class,
				() -> new QueryVersionResolution.NotReady(
						POT_ID, QueryVersionIntent.current(), OptionalLong.of(0)));
	}

	@Test
	void notApplicableRequiresAnExposableExactRequest() {
		QueryVersionResolution.NotApplicable result = new QueryVersionResolution.NotApplicable(
				POT_ID, QueryVersionIntent.exact(12), 15);

		assertEquals(QueryVersionIntent.exact(12), result.requestedVersion());
		assertEquals(15, result.latestKnownVersion());
		assertThrows(IllegalArgumentException.class,
				() -> new QueryVersionResolution.NotApplicable(POT_ID, QueryVersionIntent.current(), 15));
		assertThrows(IllegalArgumentException.class,
				() -> new QueryVersionResolution.NotApplicable(POT_ID, QueryVersionIntent.exact(16), 15));
		assertThrows(IllegalArgumentException.class,
				() -> new QueryVersionResolution.NotApplicable(POT_ID, QueryVersionIntent.exact(1), 0));
	}

	@Test
	void everyVariantRejectsNullCommonFields() {
		assertThrows(NullPointerException.class,
				() -> new QueryVersionResolution.Resolved(null, QueryVersionIntent.current(), 1, 1));
		assertThrows(NullPointerException.class,
				() -> new QueryVersionResolution.ProjectionFailed(POT_ID, null, 1, 1));
		assertThrows(NullPointerException.class,
				() -> new QueryVersionResolution.NotReady(null, QueryVersionIntent.current(), OptionalLong.empty()));
		assertThrows(NullPointerException.class,
				() -> new QueryVersionResolution.NotApplicable(POT_ID, null, 1));
	}

	@Test
	void sealedResultHasExactlyFourVariants() {
		assertEquals(4, QueryVersionResolution.class.getPermittedSubclasses().length);
	}
}
