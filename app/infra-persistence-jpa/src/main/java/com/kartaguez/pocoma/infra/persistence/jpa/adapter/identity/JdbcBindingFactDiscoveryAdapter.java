package com.kartaguez.pocoma.infra.persistence.jpa.adapter.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.engine.read.binding.BindingFactCandidate;
import com.kartaguez.pocoma.engine.read.binding.BindingFactCursor;
import com.kartaguez.pocoma.engine.read.binding.BindingFactDiscoveryPort;
import com.kartaguez.pocoma.engine.read.binding.HistoricalBindingCandidate;
import com.kartaguez.pocoma.engine.read.binding.HistoricalBindingSourcePort;

/** Short READ COMMITTED scans. Cursors are invocation-local and never persisted. */
@Component
public class JdbcBindingFactDiscoveryAdapter implements BindingFactDiscoveryPort, HistoricalBindingSourcePort {
	private final JdbcTemplate jdbc;

	public JdbcBindingFactDiscoveryAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	@Override
	public Optional<BindingFactCandidate> findNextEligible(int segmentIndex, int segmentCount,
			Instant now, Optional<BindingFactCursor> afterExclusive) {
		if (segmentCount <= 0 || segmentIndex < 0 || segmentIndex >= segmentCount) {
			throw new IllegalArgumentException("invalid segment");
		}
		String cursor = afterExclusive.isPresent() ? """
				and (fact.issuer, fact.subject, fact.binding_revision) > (?, ?, ?)
				""" : "";
		String sql = """
				select fact.event_id, fact.issuer, fact.subject, fact.binding_revision
				from external_identity_binding_facts fact
				left join consumption_slots slot
				  on slot.consumable_type = 'IDENTITY_BINDING_FACT'
				 and slot.consumable_components = jsonb_build_array(fact.event_id::text)
				 and slot.consumer_type = 'CURRENT_BINDING_PROJECTOR'
				 and slot.consumer_components = '[]'::jsonb
				left join consumption_claims claim
				  on claim.slot_id = slot.slot_id and claim.claim_id = slot.current_claim_id
				where mod(mod(fact.partition_hash, ?) + ?, ?) = ?
				  and (slot.slot_id is null or (slot.status = 'PENDING' and slot.next_claim_at <= ?
				       and (slot.current_claim_id is null or claim.lease_until <= ?)))
				%s
				order by fact.issuer, fact.subject, fact.binding_revision
				limit 1
				""".formatted(cursor);
		List<Object> args = new ArrayList<>(List.of(segmentCount, segmentCount, segmentCount, segmentIndex,
				Timestamp.from(now), Timestamp.from(now)));
		afterExclusive.ifPresent(value -> {
			args.add(value.issuer()); args.add(value.subject()); args.add(value.revision());
		});
		return jdbc.query(sql, (rs, row) -> new BindingFactCandidate(rs.getObject("event_id", UUID.class),
				new BindingFactCursor(rs.getString("issuer"), rs.getString("subject"),
						rs.getLong("binding_revision"))), args.toArray()).stream().findFirst();
	}

	@Override
	public List<HistoricalBindingCandidate> findRevisionZeroPage(Optional<ExternalIdentityCursor> afterExclusive,
			int limit) {
		String cursor = afterExclusive.isPresent() ? "and (identity.issuer, identity.subject) > (?, ?)" : "";
		String sql = """
				select identity.issuer, identity.subject, identity.user_id, identity.binding_id
				from external_identities identity
				join external_identity_binding_streams stream
				  on stream.issuer = identity.issuer and stream.subject = identity.subject
				where stream.current_revision = 0 %s
				order by identity.issuer, identity.subject limit ?
				""".formatted(cursor);
		List<Object> args = new ArrayList<>();
		afterExclusive.ifPresent(value -> { args.add(value.issuer()); args.add(value.subject()); });
		args.add(limit);
		return jdbc.query(sql, (rs, row) -> new HistoricalBindingCandidate(
				new ExternalIdentity(rs.getString("issuer"), rs.getString("subject")),
				new PocomaUserId(rs.getObject("user_id", UUID.class)),
				new BindingId(rs.getObject("binding_id", UUID.class))), args.toArray());
	}
}
