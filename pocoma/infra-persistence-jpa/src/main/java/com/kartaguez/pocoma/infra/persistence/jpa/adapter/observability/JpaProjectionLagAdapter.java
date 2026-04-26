package com.kartaguez.pocoma.infra.persistence.jpa.adapter.observability;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.kartaguez.pocoma.infra.persistence.jpa.repository.JpaPotGlobalVersionRepository;
import com.kartaguez.pocoma.observability.projection.ProjectionLagProvider;
import com.kartaguez.pocoma.observability.projection.ProjectionVersionGap;

@Component
public final class JpaProjectionLagAdapter implements ProjectionLagProvider {

	private final JpaPotGlobalVersionRepository repository;

	public JpaProjectionLagAdapter(JpaPotGlobalVersionRepository repository) {
		this.repository = Objects.requireNonNull(repository, "repository must not be null");
	}

	@Override
	public List<ProjectionVersionGap> loadProjectionVersionGaps() {
		return repository.findProjectionVersionGaps().stream()
				.map(row -> new ProjectionVersionGap(
						row.getPotId(),
						row.getCurrentVersion(),
						row.getProjectedVersion() == null ? 0 : row.getProjectedVersion()))
				.toList();
	}
}
