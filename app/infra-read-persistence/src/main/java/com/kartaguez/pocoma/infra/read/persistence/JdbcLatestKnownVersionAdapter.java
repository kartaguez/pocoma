package com.kartaguez.pocoma.infra.read.persistence;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.projection.LatestKnownVersion;
import com.kartaguez.pocoma.engine.read.projection.AdvanceLatestKnownVersionInput;
import com.kartaguez.pocoma.engine.read.projection.LatestKnownVersionPersistencePort;
import com.kartaguez.pocoma.engine.read.projection.LatestKnownVersionUpdate;

/** Maps the latest-known-version concept onto the legacy physical SQL names retained for compatibility. */
public class JdbcLatestKnownVersionAdapter implements LatestKnownVersionPersistencePort {
	private final JdbcOperations jdbc;
	private final String table;

	public JdbcLatestKnownVersionAdapter(JdbcOperations jdbc, String schema) {
		this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc must not be null");
		if (schema == null || !schema.matches("[A-Za-z_][A-Za-z0-9_]*")) {
			throw new IllegalArgumentException("invalid schema");
		}
		this.table = schema + ".source_version_watermarks";
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public LatestKnownVersionUpdate advanceToAtLeast(AdvanceLatestKnownVersionInput input) {
		List<LatestKnownVersion> changed = jdbc.query("""
				insert into %s as current_watermark (pot_id, latest_version_seen, advanced_at)
				values (?, ?, ?)
				on conflict (pot_id) do update
				set latest_version_seen = excluded.latest_version_seen,
				    advanced_at = excluded.advanced_at
				where excluded.latest_version_seen > current_watermark.latest_version_seen
				returning pot_id, latest_version_seen
				""".formatted(table), (rs, row) -> new LatestKnownVersion(
						com.kartaguez.pocoma.domain.pot.value.id.PotId.of(rs.getObject(1, java.util.UUID.class)),
						rs.getLong(2)), input.potId().value(), input.candidateVersion(), Timestamp.from(input.observedAt()));
		if (!changed.isEmpty()) return new LatestKnownVersionUpdate.Advanced(changed.getFirst());

		LatestKnownVersion current = jdbc.queryForObject(
				"select pot_id, latest_version_seen from " + table + " where pot_id = ?",
				(rs, row) -> new LatestKnownVersion(
						com.kartaguez.pocoma.domain.pot.value.id.PotId.of(rs.getObject(1, java.util.UUID.class)),
						rs.getLong(2)), input.potId().value());
		return new LatestKnownVersionUpdate.Unchanged(java.util.Objects.requireNonNull(current));
	}
}
