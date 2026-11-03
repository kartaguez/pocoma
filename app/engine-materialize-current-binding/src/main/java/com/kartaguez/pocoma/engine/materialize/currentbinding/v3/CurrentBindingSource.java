package com.kartaguez.pocoma.engine.materialize.currentbinding.v3;
import com.kartaguez.pocoma.domain.useridentity.v3.*;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import java.util.*;
public interface CurrentBindingSource {
    Optional<ExternalUserIdentity> load(UUID event,RecoveryReadContext context);
    ExternalUserIdentity at(ExternalUserIdentityId id,long version,RecoveryReadContext context);
}
