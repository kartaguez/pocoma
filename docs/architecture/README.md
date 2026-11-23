# Architecture

This section progressively introduces Pocoma's architecture:

1. **[Overview](overview.md)** — domain concepts, WRITE/READ separation, asynchronous flow, and the 10-container T1A + Core NATS topology (9 in `--wakeup-mode noop`).
2. **[Maven Modules (43 modules)](modules.md)** — layer taxonomy (`domain`, `contract`, `port`, `engine`, `orchestrator`, `supra`, `infra`, `compose`, `runtime`), dependency rules, and where to place new code.
3. **[JVM Runtimes & Concurrency](runtimes.md)** — the 5 JVM runtimes, 36 partitions, SQL leases (`30 s`), batched renewal (`renewBatch`), and fencing.
4. **[PostgreSQL Persistence](persistence.md)** — `pocoma_write` and `pocoma_read` databases, V1 SQL baselines, bootstrap verification, and coordination tables.
5. **[Security](security.md)** — OIDC authentication (Keycloak PKCE S256), application binding (`Binding`), pre-semaphore validation, HMAC idempotency, and local secret management.
6. **[READ Materialization Availability](read-availability.md)** — historical events, current markers, commit guarantees and reconstruction.
