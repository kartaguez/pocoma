/**
 * Transitional projection boundary.
 *
 * <p>{@code ComputePotBalancesUseCase} is the functional projection behavior retained behind the
 * typed balance task handler. Projection task construction, durable execution, claims and statuses
 * belong to the legacy flow and will move to task creation, task execution and consumption during
 * the worker and infrastructure migration.</p>
 */
package com.kartaguez.pocoma.engine.projection;
