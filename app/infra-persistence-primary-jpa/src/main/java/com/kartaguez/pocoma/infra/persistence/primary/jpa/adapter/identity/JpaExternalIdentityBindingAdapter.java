package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.identity;

import com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.PrimaryEventTransactionRunner;


import com.kartaguez.pocoma.port.transaction.EventRef;
import com.kartaguez.pocoma.port.transaction.EventFamily;
import com.kartaguez.pocoma.port.binding.authority.BindingAcquisitionAccepted;
import com.kartaguez.pocoma.port.binding.authority.BindingDetachmentAccepted;

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
import com.kartaguez.pocoma.port.binding.authority.ExternalIdentityBindingPort;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityDetached;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.domain.useridentity.ObservedBinding;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.identity.ExternalIdentityJdbcRepository;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.identity.ExternalIdentityBindingFactJdbcRepository;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.identity.ExternalIdentityBindingStreamJdbcRepository;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.identity.ExternalIdentityBindingOccurrenceJdbcRepository;

@Component
public class JpaExternalIdentityBindingAdapter implements ExternalIdentityBindingPort {
	private final ExternalIdentityJdbcRepository repository;
	private final ExternalIdentityBindingStreamJdbcRepository streams;
	private final ExternalIdentityBindingFactJdbcRepository facts;
	private final ExternalIdentityBindingOccurrenceJdbcRepository occurrences;
	private final Supplier<UUID> bindingIds;
    private final JdbcTemplate jdbc;
	private final ExternalIdentityBindingFactRecordMapper factMapper = new ExternalIdentityBindingFactRecordMapper();

	@Autowired
	public JpaExternalIdentityBindingAdapter(ExternalIdentityJdbcRepository repository, JdbcTemplate jdbc) {
		this(repository, jdbc, UUID::randomUUID);
	}

	// Test seam for a forced UUID collision; runtime only receives the default generator.
	JpaExternalIdentityBindingAdapter(ExternalIdentityJdbcRepository repository, JdbcTemplate jdbc,
			Supplier<UUID> bindingIds) {
		this.jdbc = requireNonNull(jdbc);
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
	@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
	public Optional<ObservedBinding> observeCurrentBinding(ExternalIdentity identity, BindingId bindingId) {
		requireNonNull(identity, "identity must not be null");
		requireNonNull(bindingId, "bindingId must not be null");
		return streams.observeExact(identity.issuer(), identity.subject(), bindingId.value())
				.map(row -> new ObservedBinding(new PocomaUserId(row.userId()), new BindingRevision(row.revision())));
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean fenceObservedBinding(ExternalIdentity identity, PocomaUserId userId,
			BindingId bindingId, BindingRevision revision) {
		requireNonNull(identity, "identity must not be null");
		requireNonNull(userId, "userId must not be null");
		requireNonNull(bindingId, "bindingId must not be null");
		requireNonNull(revision, "revision must not be null");
		return streams.fenceExact(identity.issuer(), identity.subject(), userId.value(), bindingId.value(), revision.value());
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public BindingAcquisitionAccepted acquire(ExternalIdentity identity, PocomaUserId userId) {
		return acquireWithInitializer(identity, userId, () -> {});
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public BindingAcquisitionAccepted acquireWithInitializer(ExternalIdentity identity, PocomaUserId userId,
			Runnable initializer) {
        return PrimaryEventTransactionRunner.guardedWrite(jdbc.getDataSource(), () -> {
		requireNonNull(identity, "identity must not be null");
		requireNonNull(userId, "userId must not be null");
		requireNonNull(initializer, "initializer must not be null");
		streams.createIfAbsent(identity.issuer(), identity.subject());
		long current = streams.lock(identity.issuer(), identity.subject()).currentRevision();
		if (repository.hasActiveBinding(identity.issuer(), identity.subject())) {
			return new BindingAcquisitionAccepted(BindingAcquireResult.conflict(), Optional.empty());
		}
		initializer.run();
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
		UUID eventId = UUID.randomUUID();
        facts.append(factMapper.toRow(new ExternalIdentityAttached(eventId, identity, userId,
				bindingId, new BindingRevision(next), Instant.now())));
		return new BindingAcquisitionAccepted(BindingAcquireResult.acquired(bindingId),
                Optional.of(new EventRef(EventFamily.BINDING_FACT, eventId)));

        });
    }

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public BindingDetachmentAccepted detach(ExternalIdentity identity, BindingId bindingId) {
        return PrimaryEventTransactionRunner.guardedWrite(jdbc.getDataSource(), () -> {
		requireNonNull(identity, "identity must not be null");
		requireNonNull(bindingId, "bindingId must not be null");
		if (streams.find(identity.issuer(), identity.subject()).isEmpty()) {
			return new BindingDetachmentAccepted(BindingDetachResult.NOT_CURRENT, Optional.empty());
		}
		long current = streams.lock(identity.issuer(), identity.subject()).currentRevision();
		var currentUserId = repository.findUserId(identity.issuer(), identity.subject(), bindingId.value());
		if (currentUserId.isEmpty()) return new BindingDetachmentAccepted(BindingDetachResult.NOT_CURRENT, Optional.empty());
		if (!repository.detach(identity.issuer(), identity.subject(), bindingId.value())) {
			throw new IllegalStateException("Exact Binding disappeared under the stream lock");
		}
		long next = Math.addExact(current, 1L);
		streams.advance(identity.issuer(), identity.subject(), current, next);
		UUID eventId = UUID.randomUUID();
        facts.append(factMapper.toRow(new ExternalIdentityDetached(eventId, identity,
				new PocomaUserId(currentUserId.orElseThrow()),
				bindingId, new BindingRevision(next), Instant.now())));
		return new BindingDetachmentAccepted(BindingDetachResult.DETACHED,
                Optional.of(new EventRef(EventFamily.BINDING_FACT, eventId)));

        });
    }
}
