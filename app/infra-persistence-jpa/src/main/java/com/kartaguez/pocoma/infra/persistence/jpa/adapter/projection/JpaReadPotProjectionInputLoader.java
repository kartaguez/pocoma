package com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.engine.projection.pot.ReadPotProjectionInput;
import com.kartaguez.pocoma.engine.projection.pot.ReadPotProjectionInput.ExpenseInput;
import com.kartaguez.pocoma.engine.projection.pot.ReadPotProjectionInput.ShareInput;
import com.kartaguez.pocoma.engine.projection.pot.ReadPotProjectionInput.ShareholderInput;
import com.kartaguez.pocoma.engine.projection.pot.ReadPotProjectionInputLoader;

@Component
public class JpaReadPotProjectionInputLoader implements ReadPotProjectionInputLoader {
	private final JpaHistoricalPotSnapshotSourceAdapter source;

	public JpaReadPotProjectionInputLoader(JpaHistoricalPotSnapshotSourceAdapter source) {
		this.source = requireNonNull(source, "source must not be null");
	}

	@Override
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public ReadPotProjectionInput load(ProjectionKey key) {
		requireNonNull(key, "key must not be null");
		PotId potId = PotId.of(UUID.fromString(key.targetObjectId().value()));
		var snapshot = source.load(potId, key.targetVersion());
		if (!snapshot.header().id().equals(potId)
				|| snapshot.versionMetadata().version() != key.targetVersion()) {
			throw new IllegalStateException("historical Pot snapshot does not match requested key");
		}
		var shareholders = snapshot.shareholders().stream()
				.filter(shareholder -> !shareholder.deleted())
				.map(shareholder -> new ShareholderInput(shareholder.id(), shareholder.name(),
						Optional.ofNullable(shareholder.userId()).map(user -> UserId.of(user.value())),
						shareholder.weight()))
				.toList();
		var expenses = snapshot.expenses().stream()
				.filter(expense -> !expense.header().deleted())
				.map(expense -> new ExpenseInput(expense.header().id(), expense.header().label(),
						expense.header().amount(), expense.header().date(), expense.header().payerId(),
						expense.shares().stream().map(share ->
								new ShareInput(share.shareholderId(), share.weight())).toList()))
				.toList();
		return new ReadPotProjectionInput(potId, key.targetVersion(), snapshot.header().label(),
				shareholders, expenses);
	}
}
