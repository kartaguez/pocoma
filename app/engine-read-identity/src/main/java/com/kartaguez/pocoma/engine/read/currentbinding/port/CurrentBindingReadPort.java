package com.kartaguez.pocoma.engine.read.currentbinding.port;

import java.util.Optional;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.identity.currentbinding.CurrentBinding;

/** Reads the mutable CURRENT_BINDING view. */
public interface CurrentBindingReadPort {
    Optional<CurrentBinding> find(ExternalIdentity identity);
}
