package com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.balance.Balance;
import com.kartaguez.pocoma.domain.projection.balance.PotBalances;
import com.kartaguez.pocoma.engine.port.out.query.PotBalancesQueryPort;

/** Query view backed by the immutable Balance pipeline artifacts. */
public class JpaImmutablePotBalancesQueryAdapter implements PotBalancesQueryPort {
	private final JdbcTemplate jdbc;
	private final String pipelineId;
	private final int pipelineVersion;

	public JpaImmutablePotBalancesQueryAdapter(JdbcTemplate jdbc, String pipelineId, int pipelineVersion) {
		this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc must not be null");
		this.pipelineId = requireText(pipelineId);
		if (pipelineVersion < 1) throw new IllegalArgumentException("pipelineVersion must be positive");
		this.pipelineVersion = pipelineVersion;
	}

	@Override
	@Transactional(readOnly = true)
	public PotBalances loadAtVersion(PotId potId, long version) {
		var ids = jdbc.query("""
				select projection_id from balance_projection_artifacts
				where projection_type='POT_BALANCES' and pipeline_id=? and pipeline_version=?
				  and pot_id=? and pot_version=?
				""", (row, index) -> row.getObject("projection_id", UUID.class),
				pipelineId, pipelineVersion, potId.value(), version);
		if (ids.size() != 1) {
			throw new IllegalStateException("Expected one immutable Balance projection for "
					+ pipelineId + " v" + pipelineVersion + " and " + potId + "@" + version);
		}
		Map<ShareholderId, Balance> balances = new LinkedHashMap<>();
		jdbc.query("""
				select shareholder_id,value_numerator,value_denominator
				from balance_projection_entries where projection_id=? order by shareholder_id
				""", (row, index) -> read(row), ids.getFirst())
				.forEach(balance -> balances.put(balance.shareholderId(), balance));
		return new PotBalances(potId, version, balances);
	}

	private static Balance read(ResultSet row) throws SQLException {
		ShareholderId shareholderId = ShareholderId.of(row.getObject("shareholder_id", UUID.class));
		return new Balance(shareholderId,
				Fraction.of(row.getLong("value_numerator"), row.getLong("value_denominator")));
	}

	private static String requireText(String value) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException("pipelineId must not be blank");
		return value;
	}
}
