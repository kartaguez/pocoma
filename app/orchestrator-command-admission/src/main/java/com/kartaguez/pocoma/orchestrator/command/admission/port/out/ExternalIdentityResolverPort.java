package com.kartaguez.pocoma.orchestrator.command.admission.port.out;

import java.util.Optional;

import com.kartaguez.pocoma.engine.command.model.PocomaUserId;
import com.kartaguez.pocoma.orchestrator.command.admission.model.ExternalIdentity;

public interface ExternalIdentityResolverPort {

	Optional<PocomaUserId> findUserId(ExternalIdentity identity);
}
