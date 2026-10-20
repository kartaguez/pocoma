package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.identity;

import com.kartaguez.pocoma.port.transaction.EventRef;
import com.kartaguez.pocoma.port.transaction.EventFamily;

import static java.util.Objects.requireNonNull;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityBindingFact;
import com.kartaguez.pocoma.port.binding.authority.ExternalIdentityBindingFactPort;
import com.kartaguez.pocoma.engine.materialize.currentbinding.BindingFactReadPort;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.identity.ExternalIdentityBindingFactJdbcRepository;

@Component
public class JpaExternalIdentityBindingFactAdapter implements ExternalIdentityBindingFactPort, BindingFactReadPort {
	private final ExternalIdentityBindingFactJdbcRepository repository;
	private final ExternalIdentityBindingFactRecordMapper mapper;

	public JpaExternalIdentityBindingFactAdapter(ExternalIdentityBindingFactJdbcRepository repository) {
		this.repository = requireNonNull(repository, "repository must not be null");
		this.mapper = new ExternalIdentityBindingFactRecordMapper();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public EventRef append(ExternalIdentityBindingFact fact) {
        return repository.accept(() -> {
            repository.append(mapper.toRow(fact));
            return new EventRef(EventFamily.BINDING_FACT, fact.eventId());
        });
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
	public java.util.Optional<ExternalIdentityBindingFact> findByEventId(java.util.UUID eventId) {
		requireNonNull(eventId, "eventId must not be null");
		return repository.findByEventId(eventId).map(mapper::toFact);
	}
}
