package com.kartaguez.pocoma.contract.readquery;
import com.kartaguez.pocoma.domain.projection.*;

import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import java.util.*;

public interface IdentityResultRead {
    public interface Query { Optional<IdentityTerminalResult> get(UUID request, ExternalIdentity eui); }
    java.util.Optional<com.kartaguez.pocoma.domain.projection.IdentityTerminalResult> get(java.util.UUID request, com.kartaguez.pocoma.domain.identity.ExternalIdentity eui);
}
