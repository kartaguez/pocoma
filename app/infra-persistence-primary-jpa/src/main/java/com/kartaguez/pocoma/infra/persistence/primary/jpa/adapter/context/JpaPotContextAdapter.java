package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.context;

import java.util.Optional;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.write.pot.context.AddPotShareholdersContext;
import com.kartaguez.pocoma.engine.write.pot.context.CreateExpenseContext;
import com.kartaguez.pocoma.engine.write.pot.context.DeletePotContext;
import com.kartaguez.pocoma.engine.write.pot.context.UpdatePotDetailsContext;
import com.kartaguez.pocoma.engine.write.pot.context.UpdatePotShareholdersDetailsContext;
import com.kartaguez.pocoma.engine.write.pot.context.UpdatePotShareholdersWeightsContext;
import com.kartaguez.pocoma.engine.write.pot.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.domain.pot.version.PotGlobalVersion;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.PotContextPort;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.entity.core.JpaPotHeaderEntity;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.JpaPotGlobalVersionRepository;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.core.JpaPotHeaderRepository;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.core.JpaShareholderRepository;

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
	public Optional<AddPotShareholdersContext> loadAddPotShareholdersContext(PotId potId) {
		var found = loadPotContextData(potId);
		if (found.isEmpty()) return Optional.empty();
		var contextData = found.orElseThrow();
		return Optional.of(new AddPotShareholdersContext(
				contextData.potGlobalVersion(),
				contextData.potHeader().deleted(),
				UserId.of(contextData.potHeader().creatorId())));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CreateExpenseContext> loadCreateExpenseContext(PotId potId) {
		var found = loadPotContextData(potId);
		if (found.isEmpty()) return Optional.empty();
		var contextData = found.orElseThrow();
		ShareholderRelations shareholderRelations = loadShareholderRelations(
				potId, contextData.potGlobalVersion().version());
		return Optional.of(new CreateExpenseContext(
				contextData.potGlobalVersion(),
				contextData.potHeader().deleted(),
				UserId.of(contextData.potHeader().creatorId()),
				shareholderRelations.shareholderIds(),
				shareholderRelations.shareholderUsers()));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<DeletePotContext> loadDeletePotContext(PotId potId) {
		var found = loadPotContextData(potId);
		if (found.isEmpty()) return Optional.empty();
		var contextData = found.orElseThrow();
		return Optional.of(new DeletePotContext(
				contextData.potGlobalVersion(),
				contextData.potHeader().deleted(),
				UserId.of(contextData.potHeader().creatorId())));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UpdatePotDetailsContext> loadUpdatePotDetailsContext(PotId potId) {
		var found = loadPotContextData(potId);
		if (found.isEmpty()) return Optional.empty();
		var contextData = found.orElseThrow();
		return Optional.of(new UpdatePotDetailsContext(
				contextData.potGlobalVersion(),
				contextData.potHeader().deleted(),
				UserId.of(contextData.potHeader().creatorId()),
				contextData.potHeader().startedAtVersion()));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UpdatePotShareholdersDetailsContext> loadUpdatePotShareholdersDetailsContext(PotId potId) {
		var found = loadPotContextData(potId);
		if (found.isEmpty()) return Optional.empty();
		var contextData = found.orElseThrow();
		ShareholderRelations shareholderRelations = loadShareholderRelations(
				potId, contextData.potGlobalVersion().version());
		return Optional.of(new UpdatePotShareholdersDetailsContext(
				contextData.potGlobalVersion(),
				contextData.potHeader().deleted(),
				UserId.of(contextData.potHeader().creatorId()),
				shareholderRelations.shareholderIds(),
				shareholderRelations.shareholderUsers(),
				shareholderRelations.startedAtVersions()));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UpdatePotShareholdersWeightsContext> loadUpdatePotShareholdersWeightsContext(PotId potId) {
		var found = loadPotContextData(potId);
		if (found.isEmpty()) return Optional.empty();
		var contextData = found.orElseThrow();
		ShareholderRelations relations = loadShareholderRelations(potId, contextData.potGlobalVersion().version());
		return Optional.of(new UpdatePotShareholdersWeightsContext(contextData.potGlobalVersion(), contextData.potHeader().deleted(),
				UserId.of(contextData.potHeader().creatorId()), relations.shareholderIds(), relations.startedAtVersions()));
	}

	private Optional<PotContextData> loadPotContextData(PotId potId) {
		Objects.requireNonNull(potId, "potId must not be null");
		var version = potGlobalVersionRepository.findById(potId.value());
		if (version.isEmpty()) return Optional.empty();
		PotGlobalVersion potGlobalVersion = version.orElseThrow().toDomain();
		JpaPotHeaderEntity potHeader = potHeaderRepository
				.findActiveAtVersion(potId.value(), potGlobalVersion.version())
				.orElseThrow(() -> new BusinessEntityNotFoundException(
						"POT_HEADER",
						"Pot header active at current version was not found"));
		return Optional.of(new PotContextData(potGlobalVersion, potHeader));
	}

	private ShareholderRelations loadShareholderRelations(PotId potId, long version) {
		var shareholders = shareholderRepository.findActiveNotDeletedAtVersion(potId.value(), version);
		Set<ShareholderId> shareholderIds = shareholders.stream()
				.map(shareholder -> ShareholderId.of(shareholder.shareholderId()))
				.collect(Collectors.toUnmodifiableSet());
		Map<ShareholderId, UserId> shareholderUsers = shareholders.stream()
				.filter(shareholder -> shareholder.userId() != null)
				.collect(Collectors.toUnmodifiableMap(
						shareholder -> ShareholderId.of(shareholder.shareholderId()),
						shareholder -> UserId.of(shareholder.userId())));
		Map<ShareholderId, Long> starts = shareholders.stream().collect(Collectors.toUnmodifiableMap(
				shareholder -> ShareholderId.of(shareholder.shareholderId()), shareholder -> shareholder.startedAtVersion()));
		return new ShareholderRelations(shareholderIds, shareholderUsers, starts);
	}

	private record PotContextData(PotGlobalVersion potGlobalVersion, JpaPotHeaderEntity potHeader) {
	}

	private record ShareholderRelations(
			Set<ShareholderId> shareholderIds,
			Map<ShareholderId, UserId> shareholderUsers, Map<ShareholderId, Long> startedAtVersions) {
	}
}
