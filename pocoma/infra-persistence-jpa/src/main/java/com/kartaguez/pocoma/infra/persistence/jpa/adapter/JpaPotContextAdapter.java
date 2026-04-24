package com.kartaguez.pocoma.infra.persistence.jpa.adapter;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.context.AddPotShareholdersContext;
import com.kartaguez.pocoma.engine.context.CreateExpenseContext;
import com.kartaguez.pocoma.engine.context.DeletePotContext;
import com.kartaguez.pocoma.engine.context.UpdatePotDetailsContext;
import com.kartaguez.pocoma.engine.context.UpdatePotShareholdersDetailsContext;
import com.kartaguez.pocoma.engine.context.UpdatePotShareholdersWeightsContext;
import com.kartaguez.pocoma.engine.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.out.persistence.PotContextPort;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.JpaPotHeaderEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.JpaPotGlobalVersionRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.JpaPotHeaderRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.JpaShareholderRepository;

@Component
public class JpaPotContextAdapter implements PotContextPort {

	private final JpaPotGlobalVersionRepository potGlobalVersionRepository;
	private final JpaPotHeaderRepository potHeaderRepository;
	private final JpaShareholderRepository shareholderRepository;

	public JpaPotContextAdapter(
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
	public AddPotShareholdersContext loadAddPotShareholdersContext(PotId potId) {
		PotContextData contextData = loadPotContextData(potId);
		return new AddPotShareholdersContext(
				contextData.potGlobalVersion(),
				contextData.potHeader().deleted(),
				UserId.of(contextData.potHeader().creatorId()));
	}

	@Override
	@Transactional(readOnly = true)
	public CreateExpenseContext loadCreateExpenseContext(PotId potId) {
		PotContextData contextData = loadPotContextData(potId);
		return new CreateExpenseContext(
				contextData.potGlobalVersion(),
				contextData.potHeader().deleted(),
				UserId.of(contextData.potHeader().creatorId()),
				loadShareholderIds(potId, contextData.potGlobalVersion().version()));
	}

	@Override
	@Transactional(readOnly = true)
	public DeletePotContext loadDeletePotContext(PotId potId) {
		PotContextData contextData = loadPotContextData(potId);
		return new DeletePotContext(
				contextData.potGlobalVersion(),
				contextData.potHeader().deleted(),
				UserId.of(contextData.potHeader().creatorId()));
	}

	@Override
	@Transactional(readOnly = true)
	public UpdatePotDetailsContext loadUpdatePotDetailsContext(PotId potId) {
		PotContextData contextData = loadPotContextData(potId);
		return new UpdatePotDetailsContext(
				contextData.potGlobalVersion(),
				contextData.potHeader().deleted(),
				UserId.of(contextData.potHeader().creatorId()));
	}

	@Override
	@Transactional(readOnly = true)
	public UpdatePotShareholdersDetailsContext loadUpdatePotShareholdersDetailsContext(PotId potId) {
		PotContextData contextData = loadPotContextData(potId);
		return new UpdatePotShareholdersDetailsContext(
				contextData.potGlobalVersion(),
				contextData.potHeader().deleted(),
				UserId.of(contextData.potHeader().creatorId()),
				loadShareholderIds(potId, contextData.potGlobalVersion().version()));
	}

	@Override
	@Transactional(readOnly = true)
	public UpdatePotShareholdersWeightsContext loadUpdatePotShareholdersWeightsContext(PotId potId) {
		PotContextData contextData = loadPotContextData(potId);
		return new UpdatePotShareholdersWeightsContext(
				contextData.potGlobalVersion(),
				contextData.potHeader().deleted(),
				UserId.of(contextData.potHeader().creatorId()),
				loadShareholderIds(potId, contextData.potGlobalVersion().version()));
	}

	private PotContextData loadPotContextData(PotId potId) {
		Objects.requireNonNull(potId, "potId must not be null");
		PotGlobalVersion potGlobalVersion = potGlobalVersionRepository.findById(potId.value())
				.map(entity -> entity.toDomain())
				.orElseThrow(() -> new BusinessEntityNotFoundException(
						"POT_GLOBAL_VERSION",
						"Pot global version was not found"));
		JpaPotHeaderEntity potHeader = potHeaderRepository
				.findActiveAtVersion(potId.value(), potGlobalVersion.version())
				.orElseThrow(() -> new BusinessEntityNotFoundException(
						"POT_HEADER",
						"Pot header active at current version was not found"));
		return new PotContextData(potGlobalVersion, potHeader);
	}

	private Set<ShareholderId> loadShareholderIds(PotId potId, long version) {
		return shareholderRepository.findActiveNotDeletedAtVersion(potId.value(), version).stream()
				.map(shareholder -> ShareholderId.of(shareholder.shareholderId()))
				.collect(Collectors.toUnmodifiableSet());
	}

	private record PotContextData(PotGlobalVersion potGlobalVersion, JpaPotHeaderEntity potHeader) {
	}
}
