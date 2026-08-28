/**
 * Transitional technical ordering objects still shared by durable processing
 * engines. Command ordering is owned by engine-processing-command and Event
 * ordering by engine-processing-event. Only Task ordering remains here.
 *
 * <p>These keys define claim priority, not completion order.</p>
 */
package com.kartaguez.pocoma.engine.processing.ordering;
