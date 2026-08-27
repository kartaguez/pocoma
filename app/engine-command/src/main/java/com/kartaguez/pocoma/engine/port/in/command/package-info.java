/**
 * Typed application commands and their business use cases.
 *
 * <p>A command in this package expresses a business intention. It is not the durable command
 * envelope that a pull worker may claim later. These use cases may be invoked directly by a
 * reactive supra or indirectly by a worker, and must not depend on polling, claims, leases,
 * queue persistence, or worker lifecycle.</p>
 */
package com.kartaguez.pocoma.engine.port.in.command;
