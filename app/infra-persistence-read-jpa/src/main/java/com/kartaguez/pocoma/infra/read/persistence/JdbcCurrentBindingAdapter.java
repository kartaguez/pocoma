package com.kartaguez.pocoma.infra.read.persistence;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.BindingRevision;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.engine.read.binding.CurrentBinding;
import com.kartaguez.pocoma.engine.read.binding.CurrentBindingApplyResult;
import com.kartaguez.pocoma.engine.read.binding.CurrentBindingInvariantException;
import com.kartaguez.pocoma.engine.read.binding.CurrentBindingProjectionPort;
import com.kartaguez.pocoma.engine.read.binding.CurrentBindingStatus;

public class JdbcCurrentBindingAdapter implements CurrentBindingProjectionPort {
	private final JdbcOperations jdbc;
	private final String table;

	public JdbcCurrentBindingAdapter(JdbcOperations jdbc, String schema) {
		this.jdbc = java.util.Objects.requireNonNull(jdbc);
		if (schema == null || !schema.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("invalid schema");
		this.table = schema + ".current_external_identity_binding";
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public CurrentBindingApplyResult apply(CurrentBinding value) {
		List<CurrentBinding> changed = jdbc.query("""
				insert into %s as current_binding
				(issuer, subject, binding_revision, binding_status, user_id, binding_id, source_event_id, projected_at)
				values (?, ?, ?, ?, ?, ?, ?, ?)
				on conflict (issuer, subject) do update set
				 binding_revision=excluded.binding_revision, binding_status=excluded.binding_status,
				 user_id=excluded.user_id, binding_id=excluded.binding_id,
				 source_event_id=excluded.source_event_id, projected_at=excluded.projected_at
				where excluded.binding_revision > current_binding.binding_revision
				returning issuer, subject, binding_revision, binding_status, user_id, binding_id,
				          source_event_id, projected_at
				""".formatted(table), this::map,
				value.externalIdentity().issuer(), value.externalIdentity().subject(),
				value.bindingRevision().value(), value.status().name(),
				value.userId() == null ? null : value.userId().value(),
				value.bindingId() == null ? null : value.bindingId().value(),
				value.sourceEventId(), Timestamp.from(value.projectedAt()));
		if (!changed.isEmpty()) return CurrentBindingApplyResult.APPLIED;
		CurrentBinding current = find(value.externalIdentity()).orElseThrow();
		if (value.bindingRevision().value() < current.bindingRevision().value()) return CurrentBindingApplyResult.STALE;
		if (current.samePayload(value)) return CurrentBindingApplyResult.DUPLICATE;
		throw new CurrentBindingInvariantException("Divergent CURRENT_BINDING payload at revision "
				+ value.bindingRevision().value() + " for " + value.externalIdentity());
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CurrentBinding> find(ExternalIdentity identity) {
		return jdbc.query("select issuer, subject, binding_revision, binding_status, user_id, binding_id, "
				+ "source_event_id, projected_at from " + table + " where issuer=? and subject=?",
				this::map, identity.issuer(), identity.subject()).stream().findFirst();
	}

	private CurrentBinding map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
		UUID userId = rs.getObject("user_id", UUID.class);
		return new CurrentBinding(new ExternalIdentity(rs.getString("issuer"), rs.getString("subject")),
				new BindingRevision(rs.getLong("binding_revision")),
				CurrentBindingStatus.valueOf(rs.getString("binding_status")),
				userId == null ? null : new PocomaUserId(userId),
				rs.getObject("binding_id", UUID.class) == null ? null : new BindingId(rs.getObject("binding_id", UUID.class)),
				rs.getObject("source_event_id", UUID.class), rs.getTimestamp("projected_at").toInstant());
	}
}
