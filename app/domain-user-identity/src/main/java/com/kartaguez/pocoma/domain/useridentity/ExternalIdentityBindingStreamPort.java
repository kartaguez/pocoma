package com.kartaguez.pocoma.domain.useridentity;

import java.util.Optional;

/**
 * Persistence boundary for the revision stream owned by one external identity.
 * Implementations serialize mutation by locking this stream before the authority row.
 */
public interface ExternalIdentityBindingStreamPort {

	void createIfAbsent(ExternalIdentity identity);

	Optional<BindingRevision> findCurrentRevision(ExternalIdentity identity);
}
