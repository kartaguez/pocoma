package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.command;

import com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.PrimaryEventTransactionRunner;

import com.kartaguez.pocoma.port.transaction.EventRef;
import com.kartaguez.pocoma.port.transaction.EventFamily;

import static java.util.Objects.requireNonNull;
import static org.springframework.transaction.annotation.Propagation.MANDATORY;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.contracts.command.CommandId;
import com.kartaguez.pocoma.engine.consume.command.model.CommandOutcome;
import com.kartaguez.pocoma.engine.consume.command.model.CommandTerminalEventTypes;
import com.kartaguez.pocoma.engine.consume.command.port.out.CommandOutcomePublicationPort;
import com.kartaguez.pocoma.engine.consume.command.port.out.CommandOutcomeQueryPort;
import com.kartaguez.pocoma.contracts.observability.trace.TraceContextHolder;

@Component
public class JdbcCommandOutcomeAdapter
		implements CommandOutcomePublicationPort, CommandOutcomeQueryPort {
	private final org.springframework.jdbc.core.JdbcTemplate jdbc;

	public JdbcCommandOutcomeAdapter(org.springframework.jdbc.core.JdbcTemplate jdbc) {
		this.jdbc = requireNonNull(jdbc, "jdbc must not be null");
	}

	@Override
	@Transactional(propagation = MANDATORY)
	public EventRef publish(CommandOutcome outcome) {
        return PrimaryEventTransactionRunner.guardedWrite(jdbc.getDataSource(), () -> {
		requireNonNull(outcome, "outcome must not be null");
		Shape shape = shape(outcome);
        UUID eventId = UUID.randomUUID();
		int outcomeRows = jdbc.update("""
				insert into command_outcomes
				    (command_id, outcome_type, pot_id, resulting_version, public_code, resolved_at)
				values (?, ?, ?, ?, ?, ?)
				""", outcome.commandId().value(), shape.outcomeType(), shape.potId(),
				shape.resultingVersion(), shape.publicCode(), Timestamp.from(outcome.resolvedAt()));
		if (outcomeRows != 1) throw new IllegalStateException("Outcome must insert exactly one row");
        String traceId = TraceContextHolder.current().map(value -> value.traceId()).orElse(null);
		int eventRows = jdbc.update("""
				insert into command_terminal_events
				    (event_id, event_type, command_id, command_partition_hash, trace_id, recorded_at)
				values (?, ?, ?, ?, ?, ?)
				""", eventId, shape.eventType(), outcome.commandId().value(),
				outcome.commandId().value().hashCode(), traceId, Timestamp.from(outcome.resolvedAt()));
        if (eventRows != 1) throw new IllegalStateException("Terminal Event must insert exactly one row");
        return new EventRef(EventFamily.COMMAND_TERMINAL_EVENT, eventId);

        });
    }

	@Override
	@Transactional(readOnly = true)
	public Optional<CommandOutcome> findByCommandId(CommandId commandId) {
		requireNonNull(commandId, "commandId must not be null");
		return jdbc.<CommandOutcome>query("""
				select outcome_type, pot_id, resulting_version, public_code, resolved_at
				from command_outcomes where command_id = ?
				""", (rs, row) -> switch (rs.getString("outcome_type")) {
					case "APPLIED" -> new CommandOutcome.Applied(commandId,
							rs.getObject("pot_id", UUID.class), rs.getLong("resulting_version"),
							rs.getTimestamp("resolved_at").toInstant());
					case "REJECTED" -> new CommandOutcome.Rejected(commandId,
							rs.getString("public_code"), rs.getTimestamp("resolved_at").toInstant());
					case "FAILED" -> new CommandOutcome.Failed(commandId,
							rs.getString("public_code"), rs.getTimestamp("resolved_at").toInstant());
					default -> throw new IllegalStateException("Unknown Command outcome type");
				}, commandId.value()).stream().findFirst();
	}

	private static Shape shape(CommandOutcome outcome) {
		return switch (outcome) {
			case CommandOutcome.Applied value -> new Shape("APPLIED",
					CommandTerminalEventTypes.COMMAND_APPLIED.value(), value.potId(), value.resultingVersion(), null);
			case CommandOutcome.Rejected value -> new Shape("REJECTED",
					CommandTerminalEventTypes.COMMAND_REJECTED.value(), null, null, value.rejectionCode());
			case CommandOutcome.Failed value -> new Shape("FAILED",
					CommandTerminalEventTypes.COMMAND_FAILED.value(), null, null, value.publicFailureCode());
		};
	}

	private record Shape(String outcomeType, String eventType, UUID potId, Long resultingVersion,
			String publicCode) {}
}
