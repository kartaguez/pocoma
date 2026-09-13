package com.kartaguez.pocoma.engine.port.in.query.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class QueryVersionIntentTest {

	@Test
	void createsCurrentIntentWithValueSemantics() {
		assertEquals(new QueryVersionIntent.Current(), QueryVersionIntent.current());
		assertEquals(QueryVersionIntent.current(), QueryVersionIntent.current());
	}

	@Test
	void createsExactIntentForPositiveBusinessVersion() {
		QueryVersionIntent.Exact exact = QueryVersionIntent.exact(15);

		assertEquals(15, exact.businessVersion());
		assertEquals(new QueryVersionIntent.Exact(15), exact);
		assertNotEquals(QueryVersionIntent.exact(14), exact);
	}

	@Test
	void rejectsNonPositiveExactBusinessVersion() {
		assertThrows(IllegalArgumentException.class, () -> QueryVersionIntent.exact(0));
		assertThrows(IllegalArgumentException.class, () -> QueryVersionIntent.exact(-1));
	}
}
