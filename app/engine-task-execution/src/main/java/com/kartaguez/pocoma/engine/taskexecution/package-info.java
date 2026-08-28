/**
 * Legacy worker-facing execution of durable pipeline tasks.
 *
 * <p>This transitional package accepts {@code PipelineTask}, applies pull-worker bindings and
 * delegates through a JSON-decoding strategy. New callers must use
 * {@code com.kartaguez.pocoma.engine.port.in.taskexecution} and its typed payloads. This package
 * will be removed when the task worker performs durable-to-typed mapping before invoking the
 * engine.</p>
 */
package com.kartaguez.pocoma.engine.taskexecution;
