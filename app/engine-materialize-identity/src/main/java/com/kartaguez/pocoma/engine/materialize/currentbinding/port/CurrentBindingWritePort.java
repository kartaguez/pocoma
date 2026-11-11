package com.kartaguez.pocoma.engine.materialize.currentbinding.port;

import com.kartaguez.pocoma.domain.identity.currentbinding.CurrentBinding;
import com.kartaguez.pocoma.domain.identity.currentbinding.CurrentBindingApplyResult;

/** Applies Binding facts to the mutable CURRENT_BINDING view. */
public interface CurrentBindingWritePort {
    CurrentBindingApplyResult apply(CurrentBinding binding);
}
