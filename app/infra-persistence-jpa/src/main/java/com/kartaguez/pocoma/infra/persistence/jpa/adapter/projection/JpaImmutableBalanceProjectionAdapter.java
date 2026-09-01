package com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.balance.Balance;
import com.kartaguez.pocoma.pipeline.balance.projection.BalanceProjectionArtifact;
import com.kartaguez.pocoma.pipeline.balance.projection.BalanceProjectionConflictException;
import com.kartaguez.pocoma.pipeline.balance.projection.BalanceProjectionIdentity;
import com.kartaguez.pocoma.pipeline.balance.projection.BalanceProjectionPersistenceResult;
import com.kartaguez.pocoma.pipeline.balance.projection.BalanceProjectionPort;
import com.kartaguez.pocoma.pipeline.balance.projection.BalanceProjectionReference;

@Component
public class JpaImmutableBalanceProjectionAdapter implements BalanceProjectionPort {
	private final JdbcTemplate jdbc;
	private final Clock clock;

	@Autowired
	public JpaImmutableBalanceProjectionAdapter(JdbcTemplate jdbc) { this(jdbc, Clock.systemUTC()); }
	JpaImmutableBalanceProjectionAdapter(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public BalanceProjectionPersistenceResult createOrVerify(BalanceProjectionArtifact artifact) {
		BalanceProjectionIdentity identity = artifact.identity();
		UUID proposedId = UUID.randomUUID();
		Instant now = clock.instant();
		int inserted = jdbc.update("""
				insert into balance_projection_artifacts
				(projection_id, projection_type, pipeline_id, pipeline_version, pot_id, pot_version, created_at)
				values (?, ?, ?, ?, ?, ?, ?)
				on conflict (projection_type, pipeline_id, pipeline_version, pot_id, pot_version) do nothing
				""", proposedId, identity.projectionType(), identity.pipeline().pipelineId().value(),
				identity.pipeline().pipelineVersion(), identity.potId().value(), identity.potVersion(), Timestamp.from(now));
		if (inserted == 1) {
			artifact.balances().entrySet().stream().sorted(Map.Entry.comparingByKey(
					(left, right) -> left.value().compareTo(right.value()))).forEach(entry -> jdbc.update("""
						insert into balance_projection_entries
						(projection_id, shareholder_id, value_numerator, value_denominator) values (?, ?, ?, ?)
						""", proposedId, entry.getKey().value(), entry.getValue().value().numerator(),
						entry.getValue().value().denominator()));
			return new BalanceProjectionPersistenceResult.Created(
					new BalanceProjectionReference(proposedId, identity, now));
		}

		StoredArtifact existing = load(identity);
		if (!existing.balances().equals(artifact.balances())) throw new BalanceProjectionConflictException();
		return new BalanceProjectionPersistenceResult.AlreadyPresent(existing.reference());
	}

	private StoredArtifact load(BalanceProjectionIdentity identity) {
		var references = jdbc.query("""
				select projection_id, created_at from balance_projection_artifacts
				where projection_type=? and pipeline_id=? and pipeline_version=? and pot_id=? and pot_version=?
				""", (result, row) -> new BalanceProjectionReference(result.getObject("projection_id", UUID.class),
						identity, result.getTimestamp("created_at").toInstant()), identity.projectionType(),
				identity.pipeline().pipelineId().value(), identity.pipeline().pipelineVersion(),
				identity.potId().value(), identity.potVersion());
		if (references.size() != 1) throw new IllegalStateException("Balance projection winner is not visible");
		BalanceProjectionReference reference = references.getFirst();
		Map<ShareholderId, Balance> balances = new LinkedHashMap<>();
		jdbc.query("""
				select shareholder_id, value_numerator, value_denominator
				from balance_projection_entries where projection_id=? order by shareholder_id
				""", (result, row) -> readBalance(result), reference.projectionId())
				.forEach(balance -> balances.put(balance.shareholderId(), balance));
		return new StoredArtifact(reference, Map.copyOf(balances));
	}

	private static Balance readBalance(ResultSet result) throws SQLException {
		ShareholderId id = ShareholderId.of(result.getObject("shareholder_id", UUID.class));
		return new Balance(id, Fraction.of(result.getLong("value_numerator"),
				result.getLong("value_denominator")));
	}

	private record StoredArtifact(BalanceProjectionReference reference, Map<ShareholderId, Balance> balances) {}
}
