package com.kartaguez.pocoma.infra.persistence.jpa.adapter.command;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.model.AuthorizationSnapshot;
import com.kartaguez.pocoma.engine.command.model.RecordedCommand;
import com.kartaguez.pocoma.engine.command.model.TargetCommandEnvelope;
import com.kartaguez.pocoma.engine.command.port.out.RecordedCommandAlreadyExistsException;
import com.kartaguez.pocoma.engine.command.port.out.RecordedCommandPort;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.command.JpaRecordedCommandRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.command.RecordedCommandRow;

@Component
public class JpaRecordedCommandAdapter implements RecordedCommandPort {
	private final JpaRecordedCommandRepository repository;
	private final RecordedCommandRecordMapper mapper;

	public JpaRecordedCommandAdapter(JpaRecordedCommandRepository repository, ObjectMapper objectMapper) {
		this.repository = requireNonNull(repository, "repository must not be null");
		this.mapper = new RecordedCommandRecordMapper(objectMapper);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void insert(RecordedCommand command) {
		requireNonNull(command, "command must not be null");
		RecordedCommandRow row = switch (command.envelope()) {
			case AuthorizationSnapshot legacy -> new RecordedCommandRow(
					command.commandId().value(), command.commandType().value(), command.serializedPayload(),
					command.submittedAt(), legacy.version().storageValue(), legacy.userId().value(), legacy.issuer(),
					null, null, legacy.authenticatedAt(), legacy.issuedAt(), legacy.validUntil(),
					mapper.permissionsJson(legacy.permissions()), null);
			case TargetCommandEnvelope target -> new RecordedCommandRow(
					command.commandId().value(), command.commandType().value(), command.serializedPayload(),
					command.submittedAt(), target.version().storageValue(), null,
					target.externalIdentity().issuer(), target.externalIdentity().subject(),
					target.bindingId().value(), null, null,
					target.authenticationEvidence().validUntil(), null,
					mapper.externalAuthoritiesJson(target.authenticationEvidence().externalAuthorities()));
		};
		int inserted = repository.insert(row);
		if (inserted == 0) throw new RecordedCommandAlreadyExistsException(command.commandId());
		if (inserted != 1) {
			throw new IllegalStateException("record Command expected one affected row but got " + inserted);
		}
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
	public Optional<RecordedCommand> findById(CommandId commandId) {
		return repository.findById(requireNonNull(commandId, "commandId must not be null").value())
				.map(mapper::toDomain);
	}
}
