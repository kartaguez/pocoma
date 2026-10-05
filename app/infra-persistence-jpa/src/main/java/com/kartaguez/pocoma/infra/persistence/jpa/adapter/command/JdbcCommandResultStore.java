package com.kartaguez.pocoma.infra.persistence.jpa.adapter.command;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.contracts.command.CommandId;
import com.kartaguez.pocoma.engine.read.commandresult.PublishedCommandResult;
import com.kartaguez.pocoma.engine.read.commandresult.CommandResultStore;
import com.kartaguez.pocoma.engine.read.commandresult.ImmutableCommandResult;

@Component
public final class JdbcCommandResultStore implements CommandResultStore {
	private final JdbcOperations jdbc;

	public JdbcCommandResultStore(JdbcOperations jdbc) { this.jdbc = jdbc; }

	@Override
	public void ensureResult(ImmutableCommandResult result) {
		PublishedCommandResult outcome = result.outcome();
		Shape shape = shape(outcome);
		jdbc.update("""
			insert into command_results (command_id, owner_issuer, owner_subject, schema_version,
			    outcome_type, pot_id, resulting_version, public_code, resolved_at)
			values (?, ?, ?, 1, ?, ?, ?, ?, ?)
			on conflict (command_id) do nothing
			""", outcome.commandId().value(), result.owner().issuer(), result.owner().subject(),
			shape.type, shape.potId, shape.version, shape.code, Timestamp.from(outcome.resolvedAt()));
		ImmutableCommandResult stored = find(outcome.commandId()).orElseThrow();
		if (!stored.equals(result)) {
			throw new IllegalStateException("Divergent immutable Command Result for " + outcome.commandId().value());
		}
	}

	@Override
	public Optional<ImmutableCommandResult> find(CommandId commandId) {
		return jdbc.query("""
			select owner_issuer, owner_subject, schema_version, outcome_type, pot_id,
			       resulting_version, public_code, resolved_at
			from command_results where command_id = ?
			""", (rs, row) -> {
			if (rs.getInt("schema_version") != 1) throw new IllegalStateException("Unsupported Command Result schema");
			PublishedCommandResult outcome = switch (rs.getString("outcome_type")) {
				case "APPLIED" -> new PublishedCommandResult.Applied(commandId, rs.getObject("pot_id", UUID.class),
						rs.getLong("resulting_version"), rs.getTimestamp("resolved_at").toInstant());
				case "REJECTED" -> new PublishedCommandResult.Rejected(commandId, rs.getString("public_code"),
						rs.getTimestamp("resolved_at").toInstant());
				case "FAILED" -> new PublishedCommandResult.Failed(commandId, rs.getString("public_code"),
						rs.getTimestamp("resolved_at").toInstant());
				default -> throw new IllegalStateException("Invalid Command Result outcome");
			};
			return new ImmutableCommandResult(new ExternalIdentity(rs.getString("owner_issuer"),
					rs.getString("owner_subject")), outcome);
		}, commandId.value()).stream().findFirst();
	}

	private static Shape shape(PublishedCommandResult outcome) {
		return switch (outcome) {
			case PublishedCommandResult.Applied applied -> new Shape("APPLIED", applied.potId(), applied.resultingVersion(), null);
			case PublishedCommandResult.Rejected rejected -> new Shape("REJECTED", null, null, rejected.rejectionCode());
			case PublishedCommandResult.Failed failed -> new Shape("FAILED", null, null, failed.publicFailureCode());
		};
	}

	private record Shape(String type, UUID potId, Long version, String code) {}
}
