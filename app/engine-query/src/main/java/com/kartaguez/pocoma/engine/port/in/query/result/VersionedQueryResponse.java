package com.kartaguez.pocoma.engine.port.in.query.result;

import static java.util.Objects.requireNonNull;

import java.time.Instant;

import com.kartaguez.pocoma.engine.port.in.query.version.QueryVersionIntent;

public record VersionedQueryResponse<T>(
		QueryVersionIntent requestedVersion,
		long servedVersion,
		long latestKnownVersion,
		Instant generatedAt,
		T data) {

	public VersionedQueryResponse {
		requireNonNull(requestedVersion, "requestedVersion must not be null");
		requireNonNull(generatedAt, "generatedAt must not be null");
		requireNonNull(data, "data must not be null");
		if (servedVersion < 1) {
			throw new IllegalArgumentException("servedVersion must be greater than or equal to 1");
		}
		if (latestKnownVersion < 1) {
			throw new IllegalArgumentException("latestKnownVersion must be greater than or equal to 1");
		}
		if (servedVersion > latestKnownVersion) {
			throw new IllegalArgumentException("servedVersion must be less than or equal to latestKnownVersion");
		}
		if (requestedVersion instanceof QueryVersionIntent.Exact exact
				&& servedVersion != exact.businessVersion()) {
			throw new IllegalArgumentException("servedVersion must equal the exact requested businessVersion");
		}
	}
}
