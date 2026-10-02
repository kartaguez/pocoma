package com.kartaguez.pocoma.infra.persistence.jpa.adapter.identity;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
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
import com.kartaguez.pocoma.infra.persistence.jpa.repository.identity.ExternalIdentityBindingOccurrenceJdbcRepository;

@Component
public class JpaExternalIdentityBindingAdapter implements ExternalIdentityBindingPort {
	private final ExternalIdentityJdbcRepository repository;
	private final ExternalIdentityBindingStreamJdbcRepository streams;
	private final ExternalIdentityBindingFactJdbcRepository facts;
	private final ExternalIdentityBindingOccurrenceJdbcRepository occurrences;
	private final Supplier<UUID> bindingIds;
	private final ExternalIdentityBindingFactRecordMapper factMapper = new ExternalIdentityBindingFactRecordMapper();

	@Autowired
	public JpaExternalIdentityBindingAdapter(ExternalIdentityJdbcRepository repository, JdbcTemplate jdbc) {
		this(repository, jdbc, UUID::randomUUID);
	}

	// Test seam for a forced UUID collision; runtime only receives the default generator.
	JpaExternalIdentityBindingAdapter(ExternalIdentityJdbcRepository repository, JdbcTemplate jdbc,
			Supplier<UUID> bindingIds) {
		this.repository = requireNonNull(repository, "repository must not be null");
		this.bindingIds = requireNonNull(bindingIds, "bindingIds must not be null");
		this.streams = new ExternalIdentityBindingStreamJdbcRepository(requireNonNull(jdbc, "jdbc must not be null"));
		this.facts = new ExternalIdentityBindingFactJdbcRepository(jdbc);
		this.occurrences = new ExternalIdentityBindingOccurrenceJdbcRepository(jdbc);
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
	public BindingAcquireResult acquire(ExternalIdentity identity, PocomaUserId userId) {
		requireNonNull(identity, "identity must not be null");
		requireNonNull(userId, "userId must not be null");
		streams.createIfAbsent(identity.issuer(), identity.subject());
		long current = streams.lock(identity.issuer(), identity.subject()).currentRevision();
		if (repository.hasActiveBinding(identity.issuer(), identity.subject())) {
			return BindingAcquireResult.conflict();
		}
		long next = Math.addExact(current, 1L);
		BindingId bindingId = null;
		for (int attempt = 0; attempt < 8; attempt++) {
			BindingId candidate = new BindingId(bindingIds.get());
			if (occurrences.reserve(candidate.value(), identity.issuer(), identity.subject(), userId.value(), next)) {
				bindingId = candidate;
				break;
			}
		}
		if (bindingId == null) throw new IllegalStateException("Could not reserve a fresh BindingId");
		if (!repository.acquire(identity.issuer(), identity.subject(), userId.value(), bindingId.value())) {
			throw new IllegalStateException("Binding authority changed under the stream lock");
		}
		streams.advance(identity.issuer(), identity.subject(), current, next);
		facts.append(factMapper.toRow(new ExternalIdentityAttached(UUID.randomUUID(), identity, userId,
				bindingId, new BindingRevision(next), Instant.now())));
		return BindingAcquireResult.acquired(bindingId);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public BindingDetachResult detach(ExternalIdentity identity, BindingId bindingId) {
		requireNonNull(identity, "identity must not be null");
		requireNonNull(bindingId, "bindingId must not be null");
		if (streams.find(identity.issuer(), identity.subject()).isEmpty()) {
			return BindingDetachResult.NOT_CURRENT;
		}
		long current = streams.lock(identity.issuer(), identity.subject()).currentRevision();
		var currentUserId = repository.findUserId(identity.issuer(), identity.subject(), bindingId.value());
		if (currentUserId.isEmpty()) return BindingDetachResult.NOT_CURRENT;
		if (!repository.detach(identity.issuer(), identity.subject(), bindingId.value())) {
			throw new IllegalStateException("Exact Binding disappeared under the stream lock");
		}
		long next = Math.addExact(current, 1L);
		streams.advance(identity.issuer(), identity.subject(), current, next);
		facts.append(factMapper.toRow(new ExternalIdentityDetached(UUID.randomUUID(), identity,
				new PocomaUserId(currentUserId.orElseThrow()),
				bindingId, new BindingRevision(next), Instant.now())));
		return BindingDetachResult.DETACHED;
	}
}
