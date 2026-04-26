package com.kartaguez.pocoma.observability.spring;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.kartaguez.pocoma.observability.projection.ProjectionLagProvider;
import com.kartaguez.pocoma.observability.projection.ProjectionVersionGap;

final class ProjectionLagMetrics {

	private final ProjectionLagProvider projectionLagProvider;

	ProjectionLagMetrics(ProjectionLagProvider projectionLagProvider) {
		this.projectionLagProvider = Objects.requireNonNull(projectionLagProvider, "projectionLagProvider must not be null");
	}

	double ratio(GapBucket bucket) {
		List<ProjectionVersionGap> gaps = projectionLagProvider.loadProjectionVersionGaps();
		if (gaps.isEmpty()) {
			return 0.0;
		}
		long matching = gaps.stream()
				.filter(gap -> bucket.matches(gap.gap()))
				.count();
		return (double) matching / (double) gaps.size();
	}

	Map<GapBucket, Double> snapshot() {
		List<ProjectionVersionGap> gaps = projectionLagProvider.loadProjectionVersionGaps();
		Map<GapBucket, Double> ratios = new EnumMap<>(GapBucket.class);
		for (GapBucket bucket : GapBucket.values()) {
			ratios.put(bucket, 0.0);
		}
		if (gaps.isEmpty()) {
			return ratios;
		}
		for (ProjectionVersionGap gap : gaps) {
			GapBucket.fromGap(gap.gap());
			ratios.compute(GapBucket.fromGap(gap.gap()), (ignored, current) -> current + 1.0);
		}
		ratios.replaceAll((ignored, count) -> count / (double) gaps.size());
		return ratios;
	}

	enum GapBucket {
		ZERO("0") {
			@Override
			boolean matches(long gap) {
				return gap == 0;
			}
		},
		ONE("1") {
			@Override
			boolean matches(long gap) {
				return gap == 1;
			}
		},
		TWO("2") {
			@Override
			boolean matches(long gap) {
				return gap == 2;
			}
		},
		THREE_PLUS("3_plus") {
			@Override
			boolean matches(long gap) {
				return gap >= 3;
			}
		};

		private final String tagValue;

		GapBucket(String tagValue) {
			this.tagValue = tagValue;
		}

		String tagValue() {
			return tagValue;
		}

		abstract boolean matches(long gap);

		static GapBucket fromGap(long gap) {
			if (gap == 0) {
				return ZERO;
			}
			if (gap == 1) {
				return ONE;
			}
			if (gap == 2) {
				return TWO;
			}
			return THREE_PLUS;
		}
	}
}
