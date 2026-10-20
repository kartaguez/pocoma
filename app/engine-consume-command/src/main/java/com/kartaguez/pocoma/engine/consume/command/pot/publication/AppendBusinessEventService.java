package com.kartaguez.pocoma.engine.consume.command.pot.publication;

import java.util.Objects;
import java.util.UUID;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.write.pot.port.event.BusinessEventAppendPort;
import com.kartaguez.pocoma.port.transaction.EventFamily;
import com.kartaguez.pocoma.port.transaction.EventRef;
import com.kartaguez.pocoma.port.transaction.PublishedEvents;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;

/** Explicit application owner for the auxiliary durable append, or participant in an OPEN owner. */
public final class AppendBusinessEventService {
    private final BusinessEventAppendPort writer;
    private final TransactionRunner transactions;
    private final PublishedEvents publications;

    public AppendBusinessEventService(BusinessEventAppendPort writer, TransactionRunner transactions,
            PublishedEvents publications) {
        this.writer = Objects.requireNonNull(writer);
        this.transactions = Objects.requireNonNull(transactions);
        this.publications = Objects.requireNonNull(publications);
    }

    public UUID append(BusinessEvent event) {
        return transactions.runInTransaction(() -> {
            UUID acceptedId = Objects.requireNonNull(writer.append(event), "accepted event identity");
            publications.recordPublishedEvent(new EventRef(EventFamily.BUSINESS_EVENT, acceptedId));
            return acceptedId;
        });
    }
}
