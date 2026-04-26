package com.kartaguez.pocoma.infra.persistence.jpa.adapter.core;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.entity.Shareholder;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.out.persistence.PotShareholdersPort;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.core.JpaShareholderEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.core.JpaShareholderRepository;

@Component
public class JpaPotShareholdersAdapter implements PotShareholdersPort {

	private final JpaShareholderRepository repository;

	public JpaPotShareholdersAdapter(JpaShareholderRepository repository) {
		this.repository = Objects.requireNonNull(repository, "repository must not be null");
	}

	@Override
	@Transactional(readOnly = true)
	public PotShareholders loadActiveAtVersion(PotId potId, long version) {
		Objects.requireNonNull(potId, "potId must not be null");

		List<JpaShareholderEntity> entities = repository.findActiveAtVersion(potId.value(), version);
		Set<Shareholder> shareholders = entities.stream()
				.map(JpaShareholderEntity::toDomain)
				.collect(Collectors.toSet());

		return PotShareholders.reconstitute(potId, shareholders);
	}

	@Override
	@Transactional
	public void save(PotShareholders potShareholders, PotGlobalVersion currentVersion, PotGlobalVersion nextVersion) {
		Objects.requireNonNull(potShareholders, "potShareholders must not be null");
		Objects.requireNonNull(currentVersion, "currentVersion must not be null");
		Objects.requireNonNull(nextVersion, "nextVersion must not be null");

		if (!currentVersion.potId().equals(nextVersion.potId())) {
			throw new IllegalArgumentException("nextVersion must reference the same pot as currentVersion");
		}
		if (!potShareholders.potId().equals(currentVersion.potId())) {
			throw new IllegalArgumentException("potShareholders must reference the same pot as currentVersion");
		}
		if (nextVersion.version() <= currentVersion.version()) {
			throw new IllegalArgumentException("nextVersion must be greater than currentVersion");
		}

		saveAll(potShareholders, currentVersion, nextVersion);
		saveAllNew(potShareholders, nextVersion.version());
	}

	private void saveAll(PotShareholders potShareholders, PotGlobalVersion currentVersion, PotGlobalVersion nextVersion) {
		Set<UUID> updatedShareholderIds = potShareholders.updatedShareholderIds().stream()
				.map(shareholderId -> shareholderId.value())
				.collect(Collectors.toSet());
		if (updatedShareholderIds.isEmpty()) {
			return;
		}

		int updatedRows = repository.closeActiveVersions(
				potShareholders.potId().value(),
				updatedShareholderIds,
				currentVersion.version(),
				nextVersion.version());
		if (updatedRows != updatedShareholderIds.size()) {
			throw new VersionConflictException("Pot shareholders have been modified by another operation");
		}

		List<JpaShareholderEntity> nextEntities = potShareholders.updatedShareholderIds().stream()
				.map(shareholderId -> potShareholders.shareholders().get(shareholderId))
				.map(shareholder -> JpaShareholderEntity.from(shareholder, nextVersion.version(), null))
				.toList();
		repository.saveAll(nextEntities);
	}

	private void saveAllNew(PotShareholders potShareholders, long version) {
		List<JpaShareholderEntity> newEntities = potShareholders.addedShareholderIds().stream()
				.map(shareholderId -> potShareholders.shareholders().get(shareholderId))
				.map(shareholder -> JpaShareholderEntity.from(shareholder, version, null))
				.toList();
		repository.saveAll(newEntities);
	}
}
