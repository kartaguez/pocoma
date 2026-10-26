package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import com.kartaguez.pocoma.port.transaction.v3.*;

import java.util.function.*;

/** Test instrumentation only. The delegate is the one real Spring transaction owner. */
class ForwardingTransactions implements AttemptTransactions {
    private final AttemptTransactions delegate;

    ForwardingTransactions(AttemptTransactions delegate) {
        this.delegate = delegate;
    }

    public <T> T read(Function<RecoveryReadContext, T> action) {
        return delegate.read(action);
    }

    public <T> T write(Supplier<T> action) {
        return delegate.write(action);
    }

    public void poison(Throwable cause) {
        delegate.poison(cause);
    }

    public void requirePublicationClosed() {
        delegate.requirePublicationClosed();
    }
}
