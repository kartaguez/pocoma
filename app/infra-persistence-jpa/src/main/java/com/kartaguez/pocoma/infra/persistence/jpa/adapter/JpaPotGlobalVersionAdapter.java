package com.kartaguez.pocoma.infra.persistence.jpa.adapter;

import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.JpaPotGlobalVersionEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.JpaPotGlobalVersionRepository;

@Component
public class JpaPotGlobalVersionAdapter implements PotGlobalVersionPort {

	private final JpaPotGlobalVersionRepository repository;

	public JpaPotGlobalVersionAdapter(JpaPotGlobalVersionRepository repository) {
		this.repository = Objects.requireNonNull(repository, "repository must not be null");
	}

	@Override
	@Transactional
	public void save(PotGlobalVersion potGlobalVersion) {
		repository.save(JpaPotGlobalVersionEntity.from(potGlobalVersion));
	}

	@Override
	@Transactional
	public void updateIfActive(PotGlobalVersion expectedActiveVersion, PotGlobalVersion nextVersion) {
		Objects.requireNonNull(expectedActiveVersion, "expectedActiveVersion must not be null");
		Objects.requireNonNull(nextVersion, "nextVersion must not be null");

		if (!expectedActiveVersion.potId().equals(nextVersion.potId())) {
			throw new IllegalArgumentException("nextVersion must reference the same pot as expectedActiveVersion");
		}

		int updatedRows = repository.updateIfActive(
				expectedActiveVersion.potId().value(),
				expectedActiveVersion.version(),
				nextVersion.version());

		if (updatedRows != 1) {
			throw new VersionConflictException("Pot global version has been modified by another operation");
		}
	}
}
