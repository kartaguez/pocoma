package com.kartaguez.pocoma.engine.materialize.identity;
import com.kartaguez.pocoma.domain.identity.*;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.util.*;
public interface CurrentBindingSource {
    Optional<ExternalUserIdentity> load(UUID event,RecoveryReadContext context);
    ExternalUserIdentity at(ExternalUserIdentityId id,long version,RecoveryReadContext context);
}
