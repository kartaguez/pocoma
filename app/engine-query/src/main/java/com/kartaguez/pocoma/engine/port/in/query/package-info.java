/**
 * Synchronous read use cases and their input and output models.
 *
 * <p>Queries are outside durable asynchronous processing. They may use read ports and business
 * authorization policies, but must not depend on workers, claims, leases, command queues, event
 * consumptions, or task execution.</p>
 */
package com.kartaguez.pocoma.engine.port.in.query;
