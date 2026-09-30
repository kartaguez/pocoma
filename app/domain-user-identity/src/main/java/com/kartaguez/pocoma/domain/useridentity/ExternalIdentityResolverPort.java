package com.kartaguez.pocoma.domain.useridentity;

import java.util.Optional;

/** Legacy current-binding lookup retained until WRITE_ADMISSION cuts admission over. */
public interface ExternalIdentityResolverPort {

	Optional<PocomaUserId> findUserId(ExternalIdentity identity);
}
