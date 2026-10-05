package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.identity;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.useridentity.BindingRevision;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.port.binding.authority.ExternalIdentityBindingStreamPort;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.identity.ExternalIdentityBindingStreamJdbcRepository;

@Component
public class JpaExternalIdentityBindingStreamAdapter implements ExternalIdentityBindingStreamPort {
	private final ExternalIdentityBindingStreamJdbcRepository repository;

	public JpaExternalIdentityBindingStreamAdapter(ExternalIdentityBindingStreamJdbcRepository repository) {
		this.repository = requireNonNull(repository, "repository must not be null");
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void createIfAbsent(ExternalIdentity identity) {
		requireNonNull(identity, "identity must not be null");
		repository.createIfAbsent(identity.issuer(), identity.subject());
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
	public Optional<BindingRevision> findCurrentRevision(ExternalIdentity identity) {
		requireNonNull(identity, "identity must not be null");
		return repository.find(identity.issuer(), identity.subject())
				.map(row -> new BindingRevision(row.currentRevision()));
	}
}
