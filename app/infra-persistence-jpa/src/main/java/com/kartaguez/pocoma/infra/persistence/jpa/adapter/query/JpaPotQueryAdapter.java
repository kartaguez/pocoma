package com.kartaguez.pocoma.infra.persistence.jpa.adapter.query;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.entity.Shareholder;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.out.persistence.PotQueryPort;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.core.JpaPotHeaderEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.core.JpaShareholderEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.JpaPotGlobalVersionRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.core.JpaPotHeaderRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.core.JpaShareholderRepository;

@Component
public class JpaPotQueryAdapter implements PotQueryPort {

	private final JpaPotGlobalVersionRepository potGlobalVersionRepository;
	private final JpaPotHeaderRepository potHeaderRepository;
	private final JpaShareholderRepository shareholderRepository;

	public JpaPotQueryAdapter(
			JpaPotGlobalVersionRepository potGlobalVersionRepository,
			JpaPotHeaderRepository potHeaderRepository,
			JpaShareholderRepository shareholderRepository) {
		this.potGlobalVersionRepository = Objects.requireNonNull(
				potGlobalVersionRepository,
				"potGlobalVersionRepository must not be null");
		this.potHeaderRepository = Objects.requireNonNull(potHeaderRepository, "potHeaderRepository must not be null");
		this.shareholderRepository = Objects.requireNonNull(
				shareholderRepository,
				"shareholderRepository must not be null");
	}

	@Override
	@Transactional(readOnly = true)
	public PotGlobalVersion currentVersion(PotId potId) {
		Objects.requireNonNull(potId, "potId must not be null");
		return potGlobalVersionRepository.findById(potId.value())
				.map(entity -> entity.toDomain())
				.orElseThrow(() -> new BusinessEntityNotFoundException(
						"POT_GLOBAL_VERSION",
						"Pot global version was not found"));
	}

	@Override
	@Transactional(readOnly = true)
	public PotHeader loadPotHeaderAtVersion(PotId potId, long version) {
		Objects.requireNonNull(potId, "potId must not be null");
		return potHeaderRepository.findActiveAtVersion(potId.value(), version)
				.map(JpaPotHeaderEntity::toDomain)
				.orElseThrow(() -> new BusinessEntityNotFoundException(
						"POT_HEADER",
						"Pot header active at requested version was not found"));
	}

	@Override
	@Transactional(readOnly = true)
	public PotShareholders loadPotShareholdersAtVersion(PotId potId, long version) {
		Objects.requireNonNull(potId, "potId must not be null");
		return PotShareholders.reconstitute(
				potId,
				shareholderRepository.findActiveAtVersion(potId.value(), version).stream()
						.map(JpaShareholderEntity::toDomain)
						.collect(Collectors.toSet()));
	}

	@Override
	@Transactional(readOnly = true)
	public List<VersionedPotHeader> listAccessiblePotHeaders(UserId userId) {
		Objects.requireNonNull(userId, "userId must not be null");
		return potHeaderRepository.findCurrentAccessibleNotDeletedByUserId(userId.value()).stream()
				.map(entity -> new VersionedPotHeader(entity.toDomain(), currentVersion(PotId.of(entity.potId())).version()))
				.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Shareholder> findLinkedShareholderAtVersion(UserId userId, PotId potId, long version) {
		Objects.requireNonNull(userId, "userId must not be null");
		Objects.requireNonNull(potId, "potId must not be null");
		return shareholderRepository.findLinkedUserAtVersion(potId.value(), userId.value(), version)
				.map(JpaShareholderEntity::toDomain);
	}
}
