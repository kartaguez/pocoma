package com.kartaguez.pocoma.engine.read.projection;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;

import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.value.UserId;

public record PotUserIndexQuery(
		UserId userId,
		PipelineId pipelineId,
		List<SelectedPotPipelineRange> selectedRanges,
		boolean includeDeleted,
		int limit,
		Optional<PotListCursor> after) {

	public static final int DEFAULT_LIMIT = 50;
	public static final int MAX_LIMIT = 200;

	public PotUserIndexQuery {
		requireNonNull(userId, "userId must not be null");
		requireNonNull(pipelineId, "pipelineId must not be null");
		selectedRanges = List.copyOf(requireNonNull(selectedRanges, "selectedRanges must not be null"));
		after = requireNonNull(after, "after must not be null");
		validateNonOverlappingRanges(selectedRanges);
		if (limit < 1 || limit > MAX_LIMIT) {
			throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
		}
	}

	public PotUserIndexQuery(
			UserId userId,
			PipelineId pipelineId,
			List<SelectedPotPipelineRange> selectedRanges,
			boolean includeDeleted,
			Optional<PotListCursor> after) {
		this(userId, pipelineId, selectedRanges, includeDeleted, DEFAULT_LIMIT, after);
	}

	private static void validateNonOverlappingRanges(List<SelectedPotPipelineRange> ranges) {
		for (int leftIndex = 0; leftIndex < ranges.size(); leftIndex++) {
			var left = ranges.get(leftIndex);
			long leftEnd = left.toVersionExclusive().orElse(Long.MAX_VALUE);
			for (int rightIndex = leftIndex + 1; rightIndex < ranges.size(); rightIndex++) {
				var right = ranges.get(rightIndex);
				long rightEnd = right.toVersionExclusive().orElse(Long.MAX_VALUE);
				if (left.fromVersionInclusive() < rightEnd
						&& right.fromVersionInclusive() < leftEnd) {
					throw new IllegalArgumentException("selected pipeline ranges must not overlap");
				}
			}
		}
	}
}
