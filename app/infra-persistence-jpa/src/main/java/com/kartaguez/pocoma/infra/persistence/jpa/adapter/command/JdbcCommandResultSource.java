package com.kartaguez.pocoma.infra.persistence.jpa.adapter.command;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.model.CommandOutcome;
import com.kartaguez.pocoma.engine.command.result.CommandResultSource;

@Component
public final class JdbcCommandResultSource {
	public static final String CONSUMER_TYPE = "COMMAND_RESULT_MATERIALIZER_V2";
	private final JdbcOperations jdbc;

	public JdbcCommandResultSource(JdbcOperations jdbc) { this.jdbc = jdbc; }

	/** Metadata-only discovery. The payload is reloaded before publication. */
	public Optional<Candidate> next(int segmentIndex, int segmentCount, Instant now, Optional<Cursor> after) {
		if (segmentCount <= 0 || segmentIndex < 0 || segmentIndex >= segmentCount) {
			throw new IllegalArgumentException("invalid segment");
		}
		String cursor = after.isPresent() ? "and (event.recorded_at, event.event_id) > (?, ?)" : "";
		String sql = """
			select event.event_id, event.command_id, event.recorded_at
			from command_terminal_events event
			left join consumption_slots slot
			  on slot.consumable_type = 'COMMAND_TERMINAL_EVENT'
			 and slot.consumable_components = jsonb_build_array(event.event_id::text)
			 and slot.consumer_type = 'COMMAND_RESULT_MATERIALIZER_V2'
			 and slot.consumer_components = '[]'::jsonb
			left join consumption_claims claim
			  on claim.slot_id = slot.slot_id and claim.claim_id = slot.current_claim_id
			where mod(mod(event.command_partition_hash, ?) + ?, ?) = ?
			  and (slot.slot_id is null or (slot.status = 'PENDING' and slot.next_claim_at <= ?
			       and (slot.current_claim_id is null or claim.lease_until <= ?)))
			%s
			order by event.recorded_at, event.event_id
			limit 1
			""".formatted(cursor);
		List<Object> args = new ArrayList<>(List.of(segmentCount, segmentCount, segmentCount,
				segmentIndex, Timestamp.from(now), Timestamp.from(now)));
		after.ifPresent(value -> { args.add(Timestamp.from(value.recordedAt())); args.add(value.eventId()); });
		return jdbc.query(sql, (rs, row) -> new Candidate(rs.getObject("event_id", UUID.class),
				rs.getObject("command_id", UUID.class),
				new Cursor(rs.getTimestamp("recorded_at").toInstant(), rs.getObject("event_id", UUID.class))),
				args.toArray()).stream().findFirst();
	}

	public CommandResultSource reload(UUID eventId) {
		Event event = jdbc.query("""
			select command_id, event_type from command_terminal_events where event_id = ?
			""", (rs, row) -> new Event(rs.getObject("command_id", UUID.class), rs.getString("event_type")),
				eventId).stream().findFirst().orElseThrow(() -> new IllegalStateException("Terminal Event missing"));
		CommandOutcome outcome = jdbc.query("""
			select command_id, outcome_type, pot_id, resulting_version, public_code, resolved_at
			from command_outcomes where command_id = ?
			""", (rs, row) -> {
			CommandId id = new CommandId(rs.getObject("command_id", UUID.class));
			return switch (rs.getString("outcome_type")) {
				case "APPLIED" -> new CommandOutcome.Applied(id, rs.getObject("pot_id", UUID.class),
						rs.getLong("resulting_version"), rs.getTimestamp("resolved_at").toInstant());
				case "REJECTED" -> new CommandOutcome.Rejected(id, rs.getString("public_code"),
						rs.getTimestamp("resolved_at").toInstant());
				case "FAILED" -> new CommandOutcome.Failed(id, rs.getString("public_code"),
						rs.getTimestamp("resolved_at").toInstant());
				default -> throw new IllegalStateException("Non-terminal Command outcome");
			};
		}, event.commandId()).stream().findFirst()
				.orElseThrow(() -> new IllegalStateException("Terminal Event has no outcome"));
		Recorded recorded = jdbc.query("""
			select command_id, auth_issuer, auth_subject from recorded_commands where command_id = ?
			""", (rs, row) -> new Recorded(rs.getObject("command_id", UUID.class),
				new ExternalIdentity(rs.getString("auth_issuer"), rs.getString("auth_subject"))),
				outcome.commandId().value()).stream().findFirst()
				.orElseThrow(() -> new IllegalStateException("Outcome has no recorded Command"));
		return new CommandResultSource(event.commandId(), event.eventType(), outcome,
				recorded.commandId(), recorded.requester());
	}

	public record Candidate(UUID eventId, UUID commandId, Cursor cursor) {}
	public record Cursor(Instant recordedAt, UUID eventId) {}
	private record Event(UUID commandId, String eventType) {}
	private record Recorded(UUID commandId, ExternalIdentity requester) {}
}
