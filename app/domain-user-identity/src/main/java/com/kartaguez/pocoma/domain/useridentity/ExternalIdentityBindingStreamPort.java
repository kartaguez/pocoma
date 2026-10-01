package com.kartaguez.pocoma.domain.useridentity;

import java.util.Optional;

/**
 * Persistence boundary for the revision stream owned by one external identity.
 * Revision allocation and locking are deliberately deferred to WA.6.2.
 */
public interface ExternalIdentityBindingStreamPort {

	void createIfAbsent(ExternalIdentity identity);

	Optional<BindingRevision> findCurrentRevision(ExternalIdentity identity);
}
