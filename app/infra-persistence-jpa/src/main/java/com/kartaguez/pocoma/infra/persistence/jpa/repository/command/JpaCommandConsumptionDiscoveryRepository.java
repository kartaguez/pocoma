package com.kartaguez.pocoma.infra.persistence.jpa.repository.command;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.kartaguez.pocoma.engine.command.discovery.CommandDiscoveryCursor;

@Repository
public class JpaCommandConsumptionDiscoveryRepository {
	private final JdbcTemplate jdbc;

	public JpaCommandConsumptionDiscoveryRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<CommandCandidateRow> findNextEligible(
			Instant now, Optional<CommandDiscoveryCursor> afterExclusive) {
		String cursor = afterExclusive.isPresent() ? """
				and (command.submitted_at > ?
				 or (command.submitted_at = ? and command.command_id > ?))
				""" : "";
		String sql = """
				select command.command_id, command.submitted_at
				from recorded_commands command
				left join consumption_slots slot
				  on slot.consumable_type = 'COMMAND'
				 and slot.consumable_components = jsonb_build_array(command.command_id::text)
				 and slot.consumer_type = 'COMMAND_PROCESSOR'
				 and slot.consumer_components = '[]'::jsonb
				left join consumption_claims claim
				  on claim.slot_id = slot.slot_id and claim.claim_id = slot.current_claim_id
				where (slot.slot_id is null or (
				       slot.status = 'PENDING' and slot.next_claim_at <= ?
				       and (slot.current_claim_id is null or claim.lease_until <= ?)))
				%s
				order by command.submitted_at, command.command_id
				limit 1
				""".formatted(cursor);
		List<Object> parameters = new ArrayList<>();
		parameters.add(Timestamp.from(now));
		parameters.add(Timestamp.from(now));
		afterExclusive.ifPresent(value -> {
			parameters.add(Timestamp.from(value.submittedAt()));
			parameters.add(Timestamp.from(value.submittedAt()));
			parameters.add(value.commandId().value());
		});
		return jdbc.query(sql, (result, rowNumber) -> new CommandCandidateRow(
				result.getObject("command_id", UUID.class),
				result.getTimestamp("submitted_at").toInstant()), parameters.toArray()).stream().findFirst();
	}

	public record CommandCandidateRow(UUID commandId, Instant submittedAt) {}
}
