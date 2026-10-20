package com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event;

/** Technical terminal operation. No application callback, entity or retry is permitted.
 * Implementations receive only a closed snapshot and use the current PRIMARY transaction.
 * Step 4B supplies its synthetic implementation exclusively in test sources.
 */
@FunctionalInterface
public interface PrimaryEventTransactionSuffix {
    void execute(ClosedPublishedEvents events);
}
