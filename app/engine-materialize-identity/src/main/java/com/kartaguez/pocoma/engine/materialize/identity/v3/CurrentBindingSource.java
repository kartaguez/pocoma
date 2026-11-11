package com.kartaguez.pocoma.engine.materialize.identity.v3;
import com.kartaguez.pocoma.domain.identity.v3.*;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import java.util.*;
public interface CurrentBindingSource {
    Optional<ExternalUserIdentity> load(UUID event,RecoveryReadContext context);
    ExternalUserIdentity at(ExternalUserIdentityId id,long version,RecoveryReadContext context);
}
