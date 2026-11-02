package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import com.kartaguez.pocoma.port.transaction.v3.*;

import java.util.function.*;

/** Test instrumentation only. The delegate is the one real Spring transaction owner. */
class ForwardingTransactions implements AttemptTransactions {
    private final AttemptTransactions delegate;

    ForwardingTransactions(AttemptTransactions delegate) {
        this.delegate = delegate;
    }

    public <T> T read(
            Function<RecoveryReadContext, T> action,
            com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions.Guard guard) {
        return delegate.read(action, guard);
    }

    public <T> T write(
            Supplier<T> action,
            com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions.Guard guard) {
        return delegate.write(action, guard);
    }

    public void poison(Throwable cause) {
        delegate.poison(cause);
    }

    public void requirePublicationClosed() {
        delegate.requirePublicationClosed();
    }
}
