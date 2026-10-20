package com.kartaguez.pocoma.runtime.publication;

import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.ClosedPublishedEvents;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.PrimaryEventTransactionSuffix;

/** Observation only: no database polling, publication, mutation or retry. */
public final class MeteredEventTransactionSuffix implements PrimaryEventTransactionSuffix {
    private final PrimaryEventTransactionSuffix delegate;
    private final MeterRegistry registry;
    public MeteredEventTransactionSuffix(PrimaryEventTransactionSuffix delegate, MeterRegistry registry) {
        this.delegate=java.util.Objects.requireNonNull(delegate);
        this.registry=java.util.Objects.requireNonNull(registry);
    }
    @Override public void execute(ClosedPublishedEvents batch) {
        long entered=System.nanoTime();
        long[] suffixEnded={0};
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                String outcome=switch(status) {
                    case STATUS_COMMITTED -> "committed";
                    case STATUS_ROLLED_BACK -> "rolled_back";
                    default -> "unknown";
                };
                registry.counter("pocoma.event.sequencing.spring.resolutions","outcome",outcome).increment();
                Timer.builder("pocoma.event.sequencing.suffix.to.resolution").tag("outcome",outcome)
                    .register(registry).record(System.nanoTime()-entered,TimeUnit.NANOSECONDS);
                if(suffixEnded[0]!=0) Timer.builder("pocoma.event.sequencing.commit.tail").tag("outcome",outcome)
                    .register(registry).record(System.nanoTime()-suffixEnded[0],TimeUnit.NANOSECONDS);
            }
        });
        String result="success";
        try {delegate.execute(batch);}
        catch(RuntimeException|Error failure) {result="failure";throw failure;}
        finally {
            suffixEnded[0]=System.nanoTime();
            Timer.builder("pocoma.event.sequencing.suffix").tag("result",result).register(registry)
                .record(suffixEnded[0]-entered,TimeUnit.NANOSECONDS);
        }
    }
}
