package com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.port.out.CommandOutcomeQueryPort;
import com.kartaguez.pocoma.engine.command.result.CommandResultProjectionDefinition;
import com.kartaguez.pocoma.engine.command.result.CommandResultProjectionInput;
import com.kartaguez.pocoma.engine.command.result.CommandResultProjectionInputLoader;

@Component
public class JdbcCommandResultProjectionInputLoader implements CommandResultProjectionInputLoader {
	private final CommandOutcomeQueryPort outcomes;
	private final JdbcOperations jdbc;

	public JdbcCommandResultProjectionInputLoader(CommandOutcomeQueryPort outcomes, JdbcOperations jdbc) {
		this.outcomes = requireNonNull(outcomes, "outcomes must not be null");
		this.jdbc = requireNonNull(jdbc, "jdbc must not be null");
	}

	@Override
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public CommandResultProjectionInput load(ProjectionKey key) {
		requireNonNull(key, "key must not be null");
		if (!key.projectionType().equals(CommandResultProjectionDefinition.PROJECTION_TYPE)
				|| !key.targetObjectType().equals(CommandResultProjectionDefinition.TARGET_OBJECT_TYPE)
				|| key.targetVersion() != 1) {
			throw new IllegalStateException("Unsupported COMMAND_RESULT key");
		}
		CommandId commandId = new CommandId(UUID.fromString(key.targetObjectId().value()));
		var outcome = outcomes.findByCommandId(commandId)
				.orElseThrow(() -> new IllegalStateException("Command terminal Event has no durable outcome"));
		UUID submittedBy = jdbc.queryForObject(
				"select auth_user_id from recorded_commands where command_id = ?",
				UUID.class, commandId.value());
		return new CommandResultProjectionInput(outcome,
				requireNonNull(submittedBy, "recorded Command has no authorization user"));
	}
}
