package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.kartaguez.pocoma.engine.consume.registration.RegistrationDiscoveryPort;
import com.kartaguez.pocoma.engine.consume.registration.RegistrationDiscoveryPort.Candidate;
import com.kartaguez.pocoma.engine.consume.registration.RegistrationDiscoveryPort.Cursor;

/** Metadata-only discovery; execution reloads the immutable request. */
@Component
public final class JdbcRegistrationDiscovery implements RegistrationDiscoveryPort {
    private final JdbcTemplate jdbc;
    public JdbcRegistrationDiscovery(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Optional<Candidate> next(int segmentIndex, int segmentCount, Instant now, Optional<Cursor> after) {
        if (segmentCount < 1 || segmentIndex < 0 || segmentIndex >= segmentCount) throw new IllegalArgumentException("invalid segment");
        String cursor = after.isPresent() ? "and (request.created_at, request.request_id) > (?, ?)" : "";
        String sql = """
            select request.request_id, request.created_at
            from registration_requests request
            left join consumption_slots slot
              on slot.consumable_type = 'REGISTRATION_REQUEST'
             and slot.consumable_components = jsonb_build_array(request.request_id::text)
             and slot.consumer_type = 'REGISTRATION_WORKER_V1'
             and slot.consumer_components = '[]'::jsonb
            left join consumption_claims claim
              on claim.slot_id = slot.slot_id and claim.claim_id = slot.current_claim_id
            where mod(mod(hashtext(request.request_id::text), ?) + ?, ?) = ?
              and (slot.slot_id is null or (slot.status = 'PENDING' and slot.next_claim_at <= ?
                   and (slot.current_claim_id is null or claim.lease_until <= ?)))
            %s
            order by request.created_at, request.request_id
            limit 1
            """.formatted(cursor);
        List<Object> args = new ArrayList<>(List.of(segmentCount, segmentCount, segmentCount,
                segmentIndex, Timestamp.from(now), Timestamp.from(now)));
        after.ifPresent(value -> { args.add(Timestamp.from(value.createdAt())); args.add(value.requestId()); });
        return jdbc.query(sql, (rs, n) -> new Candidate(rs.getObject(1, UUID.class),
                new Cursor(rs.getTimestamp(2).toInstant(), rs.getObject(1, UUID.class))),
                args.toArray()).stream().findFirst();
    }
}
