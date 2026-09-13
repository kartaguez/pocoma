package com.kartaguez.pocoma.engine.port.in.query.version;

import static java.util.Objects.requireNonNull;

import java.util.OptionalLong;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public sealed interface QueryVersionResolution {

	PotId potId();

	QueryVersionIntent requestedVersion();

	record Resolved(
			PotId potId,
			QueryVersionIntent requestedVersion,
			long servedVersion,
			long latestKnownVersion)
			implements QueryVersionResolution {

		public Resolved {
			validateCommon(potId, requestedVersion);
			validatePositive(servedVersion, "servedVersion");
			validatePositive(latestKnownVersion, "latestKnownVersion");
			if (servedVersion > latestKnownVersion) {
				throw new IllegalArgumentException("servedVersion must not exceed latestKnownVersion");
			}
			if (requestedVersion instanceof QueryVersionIntent.Exact exact
					&& servedVersion != exact.businessVersion()) {
				throw new IllegalArgumentException("servedVersion must equal the exact requested businessVersion");
			}
		}
	}

	record ProjectionFailed(
			PotId potId,
			QueryVersionIntent requestedVersion,
			long failedVersion,
			long latestKnownVersion)
			implements QueryVersionResolution {

		public ProjectionFailed {
			validateCommon(potId, requestedVersion);
			validatePositive(failedVersion, "failedVersion");
			validatePositive(latestKnownVersion, "latestKnownVersion");
			if (failedVersion > latestKnownVersion) {
				throw new IllegalArgumentException("failedVersion must not exceed latestKnownVersion");
			}
			if (requestedVersion instanceof QueryVersionIntent.Exact exact
					&& failedVersion != exact.businessVersion()) {
				throw new IllegalArgumentException("failedVersion must equal the exact requested businessVersion");
			}
		}
	}

	record NotReady(
			PotId potId,
			QueryVersionIntent requestedVersion,
			OptionalLong latestKnownVersion)
			implements QueryVersionResolution {

		public NotReady {
			validateCommon(potId, requestedVersion);
			requireNonNull(latestKnownVersion, "latestKnownVersion must not be null");
			if (latestKnownVersion.isPresent()) {
				validatePositive(latestKnownVersion.getAsLong(), "latestKnownVersion");
			}
		}
	}

	record NotApplicable(
			PotId potId,
			QueryVersionIntent requestedVersion,
			long latestKnownVersion)
			implements QueryVersionResolution {

		public NotApplicable {
			validateCommon(potId, requestedVersion);
			validatePositive(latestKnownVersion, "latestKnownVersion");
			if (!(requestedVersion instanceof QueryVersionIntent.Exact exact)) {
				throw new IllegalArgumentException("NotApplicable requires an exact requested version");
			}
			if (exact.businessVersion() > latestKnownVersion) {
				throw new IllegalArgumentException("exact requested businessVersion must not exceed latestKnownVersion");
			}
		}
	}

	private static void validateCommon(PotId potId, QueryVersionIntent requestedVersion) {
		requireNonNull(potId, "potId must not be null");
		requireNonNull(requestedVersion, "requestedVersion must not be null");
	}

	private static void validatePositive(long version, String name) {
		if (version < 1) {
			throw new IllegalArgumentException(name + " must be greater than or equal to 1");
		}
	}
}
