package com.kartaguez.pocoma.infra.persistence.jpa.adapter.identity;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.useridentity.BindingAcquireResult;
import com.kartaguez.pocoma.domain.useridentity.BindingDetachResult;
import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityBindingPort;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.identity.ExternalIdentityJdbcRepository;

@Component
public class JpaExternalIdentityBindingAdapter implements ExternalIdentityBindingPort {
	private final ExternalIdentityJdbcRepository repository;

	public JpaExternalIdentityBindingAdapter(ExternalIdentityJdbcRepository repository) {
		this.repository = requireNonNull(repository, "repository must not be null");
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
	public Optional<PocomaUserId> findUserId(ExternalIdentity identity, BindingId bindingId) {
		requireNonNull(identity, "identity must not be null");
		requireNonNull(bindingId, "bindingId must not be null");
		return repository.findUserId(identity.issuer(), identity.subject(), bindingId.value())
				.map(PocomaUserId::new);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<PocomaUserId> lockCurrentBinding(ExternalIdentity identity, BindingId bindingId) {
		requireNonNull(identity, "identity must not be null");
		requireNonNull(bindingId, "bindingId must not be null");
		return repository.lockCurrentBinding(identity.issuer(), identity.subject(), bindingId.value())
				.map(PocomaUserId::new);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public BindingAcquireResult acquire(ExternalIdentity identity, PocomaUserId userId, BindingId bindingId) {
		requireNonNull(identity, "identity must not be null");
		requireNonNull(userId, "userId must not be null");
		requireNonNull(bindingId, "bindingId must not be null");
		return repository.acquire(identity.issuer(), identity.subject(), userId.value(), bindingId.value())
				? BindingAcquireResult.ACQUIRED : BindingAcquireResult.CONFLICT;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public BindingDetachResult detach(ExternalIdentity identity, BindingId bindingId) {
		requireNonNull(identity, "identity must not be null");
		requireNonNull(bindingId, "bindingId must not be null");
		return repository.detach(identity.issuer(), identity.subject(), bindingId.value())
				? BindingDetachResult.DETACHED : BindingDetachResult.NOT_CURRENT;
	}
}
