# Architecture

This section progressively introduces Pocoma's architecture:

1. **[Overview](overview.md)** — domain concepts, WRITE/READ separation, asynchronous flow, and the 9-container topology.
2. **[Maven Modules (42 modules)](modules.md)** — layer taxonomy (`domain`, `contract`, `port`, `engine`, `orchestrator`, `supra`, `infra`, `compose`, `runtime`), dependency rules, and where to place new code.
3. **[JVM Runtimes & Concurrency](runtimes.md)** — the 5 JVM runtimes, 36 partitions, SQL leases (`30 s`), batched renewal (`renewBatch`), and fencing.
4. **[PostgreSQL Persistence](persistence.md)** — `pocoma_write` and `pocoma_read` databases, V1 SQL baselines, bootstrap verification, and coordination tables.
5. **[Security](security.md)** — OIDC authentication (Keycloak PKCE S256), application binding (`Binding`), pre-semaphore validation, HMAC idempotency, and local secret management.
