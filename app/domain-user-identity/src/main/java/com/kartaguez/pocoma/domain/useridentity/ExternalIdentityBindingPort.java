package com.kartaguez.pocoma.domain.useridentity;

import java.util.Optional;

/**
 * PostgreSQL-authoritative operations on the current binding occurrence.
 * Every operation participates in and requires the caller's existing transaction.
 */
public interface ExternalIdentityBindingPort {

	Optional<PocomaUserId> findUserId(ExternalIdentity identity, BindingId bindingId);

	/**
	 * Locks the exact current occurrence until the caller's surrounding transaction completes.
	 */
	Optional<PocomaUserId> lockCurrentBinding(ExternalIdentity identity, BindingId bindingId);

	BindingAcquireResult acquire(ExternalIdentity identity, PocomaUserId userId);

	BindingDetachResult detach(ExternalIdentity identity, BindingId bindingId);
}
