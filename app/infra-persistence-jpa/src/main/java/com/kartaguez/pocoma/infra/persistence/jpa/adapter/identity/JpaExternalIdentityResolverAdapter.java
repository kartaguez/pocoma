package com.kartaguez.pocoma.infra.persistence.jpa.adapter.identity;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.engine.command.model.PocomaUserId;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.identity.ExternalIdentityJdbcRepository;
import com.kartaguez.pocoma.orchestrator.command.admission.model.ExternalIdentity;
import com.kartaguez.pocoma.orchestrator.command.admission.port.out.ExternalIdentityResolverPort;

@Component
public class JpaExternalIdentityResolverAdapter implements ExternalIdentityResolverPort {
	private final ExternalIdentityJdbcRepository repository;

	public JpaExternalIdentityResolverAdapter(ExternalIdentityJdbcRepository repository) {
		this.repository = requireNonNull(repository, "repository must not be null");
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
	public Optional<PocomaUserId> findUserId(ExternalIdentity identity) {
		requireNonNull(identity, "identity must not be null");
		return repository.findUserId(identity.issuer(), identity.subject()).map(PocomaUserId::new);
	}
}
