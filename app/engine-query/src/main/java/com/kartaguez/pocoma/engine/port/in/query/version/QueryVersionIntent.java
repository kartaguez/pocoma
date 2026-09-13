package com.kartaguez.pocoma.engine.port.in.query.version;

public sealed interface QueryVersionIntent {

	record Current() implements QueryVersionIntent {}

	record Exact(long businessVersion) implements QueryVersionIntent {

		public Exact {
			if (businessVersion < 1) {
				throw new IllegalArgumentException("businessVersion must be greater than or equal to 1");
			}
		}
	}

	static Current current() {
		return new Current();
	}

	static Exact exact(long businessVersion) {
		return new Exact(businessVersion);
	}
}
