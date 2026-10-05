package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.kartaguez.pocoma.engine.materialize.registrationresult.RegistrationResultDiscovery;

/** Discovers durable terminal Outcomes; the consumer reloads the Request and Outcome. */
@Component
public final class JdbcRegistrationResultDiscovery implements RegistrationResultDiscovery {
    public static final String CONSUMER_TYPE = "REGISTRATION_RESULT_MATERIALIZER_V1";
    private final JdbcTemplate jdbc;
    public JdbcRegistrationResultDiscovery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Candidate> next(int segmentIndex, int segmentCount, Instant now, Optional<Cursor> after) {
        if (segmentCount < 1 || segmentIndex < 0 || segmentIndex >= segmentCount)
            throw new IllegalArgumentException("invalid segment");
        String cursor = after.isPresent() ? "and (outcome.decided_at, outcome.request_id) > (?, ?)" : "";
        String sql = """
            select outcome.request_id, outcome.decided_at
            from registration_outcomes outcome
            left join consumption_slots slot
              on slot.consumable_type = 'REGISTRATION_OUTCOME'
             and slot.consumable_components = jsonb_build_array(outcome.request_id::text)
             and slot.consumer_type = 'REGISTRATION_RESULT_MATERIALIZER_V1'
             and slot.consumer_components = '[]'::jsonb
            left join consumption_claims claim
              on claim.slot_id = slot.slot_id and claim.claim_id = slot.current_claim_id
            where mod(mod(hashtext(outcome.request_id::text), ?) + ?, ?) = ?
              and (slot.slot_id is null or (slot.status = 'PENDING' and slot.next_claim_at <= ?
                   and (slot.current_claim_id is null or claim.lease_until <= ?)))
            %s
            order by outcome.decided_at, outcome.request_id
            limit 1
            """.formatted(cursor);
        List<Object> args = new ArrayList<>(List.of(segmentCount, segmentCount, segmentCount,
                segmentIndex, Timestamp.from(now), Timestamp.from(now)));
        after.ifPresent(value -> { args.add(Timestamp.from(value.decidedAt())); args.add(value.requestId()); });
        return jdbc.query(sql, (rs, n) -> new Candidate(rs.getObject(1, UUID.class),
                new Cursor(rs.getTimestamp(2).toInstant(), rs.getObject(1, UUID.class))),
                args.toArray()).stream().findFirst();
    }
}
