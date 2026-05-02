package com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.projection.Balance;
import com.kartaguez.pocoma.domain.projection.PotBalances;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.engine.model.PotBalanceProjectionState;
import com.kartaguez.pocoma.engine.port.out.persistence.PotBalancesPort;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.projection.JpaPotBalanceEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.projection.JpaPotBalanceProjectionStateEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.projection.JpaPotBalanceVersionEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.projection.JpaPotBalanceProjectionStateRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.projection.JpaPotBalanceRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.projection.JpaPotBalanceVersionRepository;

@Component
public class JpaPotBalancesAdapter implements PotBalancesPort {

	private final JpaPotBalanceRepository balanceRepository;
	private final JpaPotBalanceVersionRepository versionRepository;
	private final JpaPotBalanceProjectionStateRepository stateRepository;

	public JpaPotBalancesAdapter(
			JpaPotBalanceRepository balanceRepository,
			JpaPotBalanceVersionRepository versionRepository,
			JpaPotBalanceProjectionStateRepository stateRepository) {
		this.balanceRepository = Objects.requireNonNull(balanceRepository, "balanceRepository must not be null");
		this.versionRepository = Objects.requireNonNull(versionRepository, "versionRepository must not be null");
		this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository must not be null");
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<PotBalanceProjectionState> loadProjectionState(PotId potId) {
		Objects.requireNonNull(potId, "potId must not be null");
		return stateRepository.findById(potId.value())
				.map(JpaPotBalanceProjectionStateEntity::toDomain);
	}

	@Override
	@Transactional(readOnly = true)
	public PotBalances loadAtVersion(PotId potId, long version) {
		Objects.requireNonNull(potId, "potId must not be null");
		if (!versionRepository.existsByPotIdAndVersion(potId.value(), version)) {
			throw new BusinessEntityNotFoundException(
					"POT_BALANCES",
					"Pot balances were not found at requested version");
		}

		Map<ShareholderId, Balance> balances = balanceRepository.findByPotIdAndVersion(potId.value(), version).stream()
				.map(JpaPotBalanceEntity::toDomain)
				.collect(Collectors.toMap(Balance::shareholderId, balance -> balance));

		return new PotBalances(potId, version, balances);
	}

	@Override
	@Transactional
	public void saveInitial(PotBalances potBalances) {
		Objects.requireNonNull(potBalances, "potBalances must not be null");
		if (stateRepository.existsById(potBalances.potId().value())) {
			throw new VersionConflictException("Pot balances projection state already exists");
		}
		saveNewVersion(potBalances);
		stateRepository.save(JpaPotBalanceProjectionStateEntity.from(new PotBalanceProjectionState(
				potBalances.potId(),
				potBalances.version())));
	}

	@Override
	@Transactional
	public void save(PotBalances potBalances, long expectedProjectedVersion) {
		Objects.requireNonNull(potBalances, "potBalances must not be null");
		if (potBalances.version() <= expectedProjectedVersion) {
			throw new IllegalArgumentException("potBalances version must be greater than expectedProjectedVersion");
		}

		saveNewVersion(potBalances);
		int updatedRows = stateRepository.updateIfProjectedVersion(
				potBalances.potId().value(),
				expectedProjectedVersion,
				potBalances.version());

		if (updatedRows != 1) {
			throw new VersionConflictException("Pot balances projection state has been modified by another operation");
		}
	}

	@Override
	@Transactional
	public void saveFull(PotBalances potBalances) {
		Objects.requireNonNull(potBalances, "potBalances must not be null");
		saveNewVersionIfAbsent(potBalances);

		Optional<PotBalanceProjectionState> projectionState = loadProjectionState(potBalances.potId());
		if (projectionState.isEmpty()) {
			stateRepository.save(JpaPotBalanceProjectionStateEntity.from(new PotBalanceProjectionState(
					potBalances.potId(),
					potBalances.version())));
			return;
		}
		long projectedVersion = projectionState.get().projectedVersion();
		if (potBalances.version() > projectedVersion) {
			int updatedRows = stateRepository.updateIfProjectedVersion(
					potBalances.potId().value(),
					projectedVersion,
					potBalances.version());
			if (updatedRows != 1) {
				throw new VersionConflictException("Pot balances projection state has been modified by another operation");
			}
		}
	}

	private void saveNewVersion(PotBalances potBalances) {
		try {
			UUID potId = potBalances.potId().value();
			versionRepository.saveAndFlush(new JpaPotBalanceVersionEntity(potId, potBalances.version()));
			List<JpaPotBalanceEntity> entities = potBalances.balances().values().stream()
					.map(balance -> JpaPotBalanceEntity.from(potId, potBalances.version(), balance))
					.toList();
			balanceRepository.saveAllAndFlush(entities);
		}
		catch (DataIntegrityViolationException exception) {
			throw new VersionConflictException("Pot balances already exist at requested version");
		}
	}

	private void saveNewVersionIfAbsent(PotBalances potBalances) {
		if (versionRepository.existsByPotIdAndVersion(potBalances.potId().value(), potBalances.version())) {
			return;
		}
		try {
			saveNewVersion(potBalances);
		}
		catch (VersionConflictException exception) {
			if (!versionRepository.existsByPotIdAndVersion(potBalances.potId().value(), potBalances.version())) {
				throw exception;
			}
		}
	}
}
