package com.kartaguez.pocoma.engine.service.query.version;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.OptionalLong;

import com.kartaguez.pocoma.domain.pipeline.PipelineVersionDefinition;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.LatestKnownVersion;
import com.kartaguez.pocoma.domain.projection.ProjectionGenerationIdentity;
import com.kartaguez.pocoma.domain.projection.ProjectionIdentity;
import com.kartaguez.pocoma.domain.projection.ProjectionStatus;
import com.kartaguez.pocoma.engine.port.in.query.version.QueryProjectionSelection;
import com.kartaguez.pocoma.engine.port.in.query.version.QueryVersionIntent;
import com.kartaguez.pocoma.engine.port.in.query.version.QueryVersionResolution;
import com.kartaguez.pocoma.engine.port.out.query.LatestKnownVersionQueryPort;
import com.kartaguez.pocoma.engine.port.out.query.ProjectionReadinessQueryPort;
import com.kartaguez.pocoma.engine.port.out.query.TerminalProjectionState;

public final class QueryVersionResolver {

	private final LatestKnownVersionQueryPort latestKnownVersionQueryPort;
	private final ProjectionReadinessQueryPort projectionReadinessQueryPort;

	public QueryVersionResolver(
			LatestKnownVersionQueryPort latestKnownVersionQueryPort,
			ProjectionReadinessQueryPort projectionReadinessQueryPort) {
		this.latestKnownVersionQueryPort = requireNonNull(
				latestKnownVersionQueryPort,
				"latestKnownVersionQueryPort must not be null");
		this.projectionReadinessQueryPort = requireNonNull(
				projectionReadinessQueryPort,
				"projectionReadinessQueryPort must not be null");
	}

	public QueryVersionResolution resolve(
			PotId potId,
			QueryVersionIntent requestedVersion,
			QueryProjectionSelection projectionSelection) {
		requireNonNull(potId, "potId must not be null");
		requireNonNull(requestedVersion, "requestedVersion must not be null");
		requireNonNull(projectionSelection, "projectionSelection must not be null");

		Optional<LatestKnownVersion> latestKnown = latestKnownVersionQueryPort.findByPotId(potId);
		if (latestKnown.isEmpty()) {
			return new QueryVersionResolution.NotReady(potId, requestedVersion, OptionalLong.empty());
		}

		long latestKnownVersion = latestKnown.orElseThrow().latestKnownVersion();
		if (requestedVersion instanceof QueryVersionIntent.Current) {
			return resolveCurrent(potId, requestedVersion, projectionSelection, latestKnownVersion);
		}
		return resolveExact(
				potId,
				(QueryVersionIntent.Exact) requestedVersion,
				projectionSelection,
				latestKnownVersion);
	}

	private QueryVersionResolution resolveCurrent(
			PotId potId,
			QueryVersionIntent requestedVersion,
			QueryProjectionSelection projectionSelection,
			long latestKnownVersion) {
		ProjectionGenerationIdentity generation = generation(potId, projectionSelection);
		Optional<TerminalProjectionState> terminal = projectionReadinessQueryPort
				.findHighestTerminalAtOrBelow(generation, latestKnownVersion);
		if (terminal.isEmpty()) {
			return new QueryVersionResolution.NotReady(
					potId,
					requestedVersion,
					OptionalLong.of(latestKnownVersion));
		}

		TerminalProjectionState state = terminal.orElseThrow();
		if (state.status() == ProjectionStatus.READY) {
			return new QueryVersionResolution.Resolved(
					potId,
					requestedVersion,
					state.businessVersion(),
					latestKnownVersion);
		}
		return new QueryVersionResolution.ProjectionFailed(
				potId,
				requestedVersion,
				state.businessVersion(),
				latestKnownVersion);
	}

	private QueryVersionResolution resolveExact(
			PotId potId,
			QueryVersionIntent.Exact requestedVersion,
			QueryProjectionSelection projectionSelection,
			long latestKnownVersion) {
		long exactVersion = requestedVersion.businessVersion();
		if (exactVersion > latestKnownVersion) {
			return new QueryVersionResolution.NotReady(
					potId,
					requestedVersion,
					OptionalLong.of(latestKnownVersion));
		}

		PipelineVersionDefinition servingPipeline = projectionSelection.servingPipeline();
		if (!servingPipeline.appliesTo(exactVersion)) {
			return new QueryVersionResolution.NotApplicable(
					potId,
					requestedVersion,
					latestKnownVersion);
		}

		ProjectionIdentity identity = new ProjectionIdentity(generation(potId, projectionSelection), exactVersion);
		return switch (projectionReadinessQueryPort.statusAt(identity)) {
			case READY -> new QueryVersionResolution.Resolved(
					potId,
					requestedVersion,
					exactVersion,
					latestKnownVersion);
			case FAILED -> new QueryVersionResolution.ProjectionFailed(
					potId,
					requestedVersion,
					exactVersion,
					latestKnownVersion);
			case NOT_READY -> new QueryVersionResolution.NotReady(
					potId,
					requestedVersion,
					OptionalLong.of(latestKnownVersion));
		};
	}

	private static ProjectionGenerationIdentity generation(
			PotId potId,
			QueryProjectionSelection projectionSelection) {
		return new ProjectionGenerationIdentity(
				projectionSelection.projectionType(),
				projectionSelection.servingPipeline().identity(),
				potId);
	}
}
