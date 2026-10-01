package com.kartaguez.pocoma.infra.persistence.jpa.adapter.identity;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import com.kartaguez.pocoma.domain.useridentity.BindingAcquireResult;
import com.kartaguez.pocoma.domain.useridentity.BindingDetachResult;
import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.BindingRevision;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityAttached;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityBindingPort;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityDetached;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.identity.ExternalIdentityJdbcRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.identity.ExternalIdentityBindingFactJdbcRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.identity.ExternalIdentityBindingStreamJdbcRepository;

@Component
public class JpaExternalIdentityBindingAdapter implements ExternalIdentityBindingPort {
	private final ExternalIdentityJdbcRepository repository;
	private final ExternalIdentityBindingStreamJdbcRepository streams;
	private final ExternalIdentityBindingFactJdbcRepository facts;
	private final ExternalIdentityBindingFactRecordMapper factMapper = new ExternalIdentityBindingFactRecordMapper();

	public JpaExternalIdentityBindingAdapter(ExternalIdentityJdbcRepository repository, JdbcTemplate jdbc) {
		this.repository = requireNonNull(repository, "repository must not be null");
		this.streams = new ExternalIdentityBindingStreamJdbcRepository(requireNonNull(jdbc, "jdbc must not be null"));
		this.facts = new ExternalIdentityBindingFactJdbcRepository(jdbc);
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
		streams.createIfAbsent(identity.issuer(), identity.subject());
		long current = streams.lock(identity.issuer(), identity.subject()).currentRevision();
		if (!repository.acquire(identity.issuer(), identity.subject(), userId.value(), bindingId.value())) {
			return BindingAcquireResult.CONFLICT;
		}
		long next = Math.addExact(current, 1L);
		streams.advance(identity.issuer(), identity.subject(), current, next);
		facts.append(factMapper.toRow(new ExternalIdentityAttached(UUID.randomUUID(), identity, userId,
				bindingId, new BindingRevision(next), Instant.now())));
		return BindingAcquireResult.ACQUIRED;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public BindingDetachResult detach(ExternalIdentity identity, BindingId bindingId) {
		requireNonNull(identity, "identity must not be null");
		requireNonNull(bindingId, "bindingId must not be null");
		streams.createIfAbsent(identity.issuer(), identity.subject());
		long current = streams.lock(identity.issuer(), identity.subject()).currentRevision();
		if (!repository.detach(identity.issuer(), identity.subject(), bindingId.value())) {
			return BindingDetachResult.NOT_CURRENT;
		}
		long next = Math.addExact(current, 1L);
		streams.advance(identity.issuer(), identity.subject(), current, next);
		facts.append(factMapper.toRow(new ExternalIdentityDetached(UUID.randomUUID(), identity,
				bindingId, new BindingRevision(next), Instant.now())));
		return BindingDetachResult.DETACHED;
	}
}
