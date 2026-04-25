package com.kartaguez.pocoma.infra.persistence.jpa.adapter.core;

import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.out.persistence.PotHeaderPort;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.core.JpaPotHeaderEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.core.JpaPotHeaderRepository;

@Component
public class JpaPotHeaderAdapter implements PotHeaderPort {

	private final JpaPotHeaderRepository repository;

	public JpaPotHeaderAdapter(JpaPotHeaderRepository repository) {
		this.repository = Objects.requireNonNull(repository, "repository must not be null");
	}

	@Override
	@Transactional(readOnly = true)
	public PotHeader loadActiveAtVersion(PotId potId, long version) {
		Objects.requireNonNull(potId, "potId must not be null");
		return repository.findActiveAtVersion(potId.value(), version)
				.map(JpaPotHeaderEntity::toDomain)
				.orElseThrow(() -> new BusinessEntityNotFoundException(
						"POT_HEADER",
						"Pot header active at requested version was not found"));
	}

	@Override
	@Transactional
	public void saveNew(PotHeader potHeader, long version) {
		Objects.requireNonNull(potHeader, "potHeader must not be null");
		repository.save(JpaPotHeaderEntity.from(potHeader, version, null));
	}

	@Override
	@Transactional
	public void save(PotHeader potHeader, PotGlobalVersion currentVersion, PotGlobalVersion nextVersion) {
		Objects.requireNonNull(potHeader, "potHeader must not be null");
		Objects.requireNonNull(currentVersion, "currentVersion must not be null");
		Objects.requireNonNull(nextVersion, "nextVersion must not be null");

		if (!currentVersion.potId().equals(nextVersion.potId())) {
			throw new IllegalArgumentException("nextVersion must reference the same pot as currentVersion");
		}
		if (!potHeader.id().equals(currentVersion.potId())) {
			throw new IllegalArgumentException("potHeader must reference the same pot as currentVersion");
		}
		if (nextVersion.version() <= currentVersion.version()) {
			throw new IllegalArgumentException("nextVersion must be greater than currentVersion");
		}

		int updatedRows = repository.closeActiveVersion(
				potHeader.id().value(),
				currentVersion.version(),
				nextVersion.version());

		if (updatedRows != 1) {
			throw new VersionConflictException("Pot header has been modified by another operation");
		}

		repository.save(JpaPotHeaderEntity.from(potHeader, nextVersion.version(), null));
	}
}
