package com.kartaguez.pocoma.infra.persistence.jpa.repository;

import java.util.UUID;

public interface ProjectionVersionGapRow {

	UUID getPotId();

	long getCurrentVersion();

	Long getProjectedVersion();
}
