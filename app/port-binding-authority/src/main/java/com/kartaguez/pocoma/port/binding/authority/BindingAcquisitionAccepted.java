package com.kartaguez.pocoma.port.binding.authority;

import java.util.Objects;
import java.util.Optional;

import com.kartaguez.pocoma.domain.useridentity.BindingAcquireResult;
import com.kartaguez.pocoma.port.transaction.EventFamily;
import com.kartaguez.pocoma.port.transaction.EventRef;

/** TARGET authority result plus its winning fact acceptance; CURRENT signatures migrate in 4D.
 * Conflict/not-current has no new publication. The domain result remains unchanged.
 * The fact UUID is preserved from the accepted mutation, never regenerated here.
 */
public record BindingAcquisitionAccepted(BindingAcquireResult result, Optional<EventRef> fact) {
    public BindingAcquisitionAccepted {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(fact, "fact");
        if ((result.status() == BindingAcquireResult.Status.ACQUIRED) != fact.isPresent()
                || fact.filter(ref -> ref.family() != EventFamily.BINDING_FACT).isPresent()) {
            throw new IllegalArgumentException("Only a winning mutation accepts one Binding fact");
        }
    }
}
