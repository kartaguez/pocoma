package com.kartaguez.pocoma.port.binding.authority;

import com.kartaguez.pocoma.domain.useridentity.BindingAcquireResult;
import com.kartaguez.pocoma.domain.useridentity.BindingDetachResult;
import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.BindingRevision;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.ObservedBinding;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;

import java.util.Optional;

/**
 * PostgreSQL-authoritative operations on the current binding occurrence.
 * Every operation participates in and requires the caller's existing transaction.
 */
public interface ExternalIdentityBindingPort {

	Optional<PocomaUserId> findUserId(ExternalIdentity identity, BindingId bindingId);

	/** A single non-locking snapshot of the current WRITE authority and its stream revision. */
	Optional<ObservedBinding> observeCurrentBinding(ExternalIdentity identity, BindingId bindingId);

	/** Final PostgreSQL CAS, in the caller's business transaction. */
	boolean fenceObservedBinding(ExternalIdentity identity, PocomaUserId userId,
			BindingId bindingId, BindingRevision revision);

	BindingAcquireResult acquire(ExternalIdentity identity, PocomaUserId userId);

	/** Acquire under the same stream lock, invoking the initializer only after availability is proven.
	 * The initializer and the acquisition participate in the caller's single transaction. */
	BindingAcquireResult acquireWithInitializer(ExternalIdentity identity, PocomaUserId userId, Runnable initializer);

	BindingDetachResult detach(ExternalIdentity identity, BindingId bindingId);
}
