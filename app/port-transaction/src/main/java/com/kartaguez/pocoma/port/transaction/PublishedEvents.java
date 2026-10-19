package com.kartaguez.pocoma.port.transaction;

/**
 * Application declarations of accepted publications requiring durable ordering.
 * Only orchestration calls this view, after a compatible publication port accepts.
 * Compatibility is certified by explicit composition, never inferred from an UUID
 * or a successful non-transactional send. Acceptance is not commit or delivery.
 *
 * Calls preserve execution order within each family, across participants and calls.
 * Duplicate references are errors. Outside a compatible owner or after closure,
 * declarations must fail and make an existing transaction non-committable.
 * This interface has no production implementation in step 4A; owner lifecycle,
 * poisoning and cleanup belong to step 4B. No implicit adapter collection is allowed.
 * Non-ordered publications use their publisher without this dependency.
 */
@FunctionalInterface
public interface PublishedEvents {
    void recordPublishedEvent(EventRef event);
}
