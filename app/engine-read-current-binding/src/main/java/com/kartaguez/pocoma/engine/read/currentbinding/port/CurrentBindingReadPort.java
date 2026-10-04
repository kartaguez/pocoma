package com.kartaguez.pocoma.engine.read.currentbinding.port;

import java.util.Optional;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.currentbinding.CurrentBinding;

/** Reads the mutable CURRENT_BINDING view. */
public interface CurrentBindingReadPort {
    Optional<CurrentBinding> find(ExternalIdentity identity);
}
