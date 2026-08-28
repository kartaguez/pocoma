/**
 * Legacy serialized event representations retained for the current outbox workers.
 * New processing uses {@code RecordedEvent} and typed Pot business events. This package can be
 * removed once the legacy event workers and materialization flow have been migrated.
 */
package com.kartaguez.pocoma.engine.legacy.event;
