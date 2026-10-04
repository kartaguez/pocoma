package com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection;

import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.projector.pot.AuthProjectionInput;
import com.kartaguez.pocoma.engine.projection.pot.AuthProjectionInputLoader;
import com.kartaguez.pocoma.engine.consume.projectiontask.historical.HistoricalPotReconstructionException;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.JpaPotGlobalVersionRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.core.JpaPotHeaderRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.core.JpaShareholderRepository;

@Component
public class JpaAuthProjectionInputLoader implements AuthProjectionInputLoader {
	private final JpaPotGlobalVersionRepository versions;
	private final JpaPotHeaderRepository pots;
	private final JpaShareholderRepository shareholders;

	public JpaAuthProjectionInputLoader(JpaPotGlobalVersionRepository versions,
			JpaPotHeaderRepository pots, JpaShareholderRepository shareholders) {
		this.versions = requireNonNull(versions, "versions must not be null");
		this.pots = requireNonNull(pots, "pots must not be null");
		this.shareholders = requireNonNull(shareholders, "shareholders must not be null");
	}

	@Override
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public AuthProjectionInput load(ProjectionKey key) {
		requireNonNull(key, "key must not be null");
		PotId potId = PotId.of(UUID.fromString(key.targetObjectId().value()));
		long version = key.targetVersion();
		versions.findVersionCreatedAt(potId.value(), version).orElseThrow(() -> reconstruction(
				"POT_VERSION_METADATA_ABSENT", "No Pot version metadata at requested version"));
		var header = pots.findActiveAtVersion(potId.value(), version).orElseThrow(() -> reconstruction(
				"POT_HEADER_ABSENT", "No Pot header at requested version"));
		var rows = shareholders.findActiveAtVersion(potId.value(), version);
		var shareholderIds = rows.stream().map(row -> row.shareholderId()).toList();
		if (new HashSet<>(shareholderIds).size() != shareholderIds.size()) {
			throw reconstruction("DUPLICATE_SHAREHOLDER", "Several active rows for one shareholder identity");
		}
		var activeRelations = rows.stream()
				.filter(row -> !row.deleted() && row.userId() != null)
				.collect(Collectors.toUnmodifiableMap(
						row -> ShareholderId.of(row.shareholderId()),
						row -> UserId.of(row.userId())));
		return new AuthProjectionInput(potId, version, UserId.of(header.creatorId()), activeRelations);
	}

	private static HistoricalPotReconstructionException reconstruction(String code, String message) {
		return new HistoricalPotReconstructionException(code, message);
	}
}
