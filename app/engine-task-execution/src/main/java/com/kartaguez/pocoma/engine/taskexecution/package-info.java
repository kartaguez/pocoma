/**
 * Framework-free execution of durable pipeline tasks.
 *
 * <p>Recorded tasks are decoded to typed payloads before entering this engine. Handlers return
 * functional reports; consumption fencing and persistence provenance remain responsibilities of
 * the specialized locator and consumption engine.</p>
 */
package com.kartaguez.pocoma.engine.taskexecution;
