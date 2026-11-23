# Security, Authentication, and Secrets Management

Access to `/api/v3` endpoints relies on OIDC authentication delegated to Keycloak and a clear separation between OIDC identity, application binding (`Binding`), and local secret management.

## OIDC Authentication (Keycloak, Code + PKCE S256)

The local infrastructure deploys **Keycloak `26.7.0`** (`quay.io/keycloak/keycloak:26.7.0`, limit `896m` / `1.5` CPU, heap `-Xms128m -Xmx512m`) imported with the [`docker/keycloak/pocoma-realm.json`](../../docker/keycloak/pocoma-realm.json) realm:

- **User & Bruno GUI flow**: Authorization Code + PKCE (`S256`). `./pocoma-local gui` / `./pocoma-local login` performs native OAuth authentication without manual JWT copying (see [Authentication & Binding](../guides/authentication.md)).
- **Accounts provisioned for the local playground only**:
  - Keycloak admin console: `admin` / `pocoma`
  - Test application users: `third`, `guest`, `bruno` (password `pocoma`)

> [!WARNING]
> These credentials apply exclusively to the local development environment managed by `docker/keycloak/pocoma-realm.json` and must never be reused in a shared or exposed environment.

## Application Authorization & HTTP Boundaries

On the web runtimes, the security configuration (`HttpSecurityConfiguration`, `AdmissionHttpConfiguration`, `ReadHttpConfiguration`) enforces:

1. **Bearer JWT authentication**: validates tokens issued by Keycloak on `/api/v3` application routes.
2. **Application binding (`Binding`)**: explicit link between the authenticated subject and the internal identifiers manipulating pots (see [Authentication & Binding](../guides/authentication.md) and [Public API `/api/v3` Reference](../reference/api.md)).
3. **Pre-semaphore validation on `http-read`**: HTTP protocol and access validation runs **before** acquiring concurrency permits (`Q=8`), preventing bursts of invalid or unauthorized requests from saturating READ SQL connections.

## Idempotency & Replay Protection

Write operations exposed on `/api/v3` use idempotency mechanisms (idempotency key and HMAC payload-fingerprint verification at admission) to guarantee that replaying an identical submission produces no duplicate effects and that a payload mismatch is rejected with the contractual HTTP statuses documented in [Public API `/api/v3` Reference](../reference/api.md).

## Local Secrets & Log Redaction

When an instance is created by `./pocoma-local start`, local secrets (database technical passwords, instance-specific client/session keys) are generated in the instance's private directory under `.pocoma-local/instances/<instance>/` (untracked by Git).

- **Restart protection**: if an existing instance is restarted without its expected local secrets, `./pocoma-local` fails with `PRE-SECRETS-MISSING` rather than silently regenerating secrets inconsistent with PostgreSQL or Keycloak.
- **Log redaction**: operational utilities and diagnostic reports redact secrets and tokens in logs persisted to disk. See [Troubleshooting & Diagnostics](../operations/troubleshooting.md) for Keycloak-related diagnostics.

## Internal Wakeup Broker Security (Core NATS)

Core NATS (`nats:2.15.3-alpine`) is deployed strictly as an **internal-only** infrastructure service for volatile wakeup hints:

- **No public host port**: neither the client port (`4222`) nor the monitoring port (`8222`) is published to the host by default.
- **Per-instance technical authentication**: `./pocoma-local` generates a 32-byte random URL-safe NATS token in `secrets.json`, injects it into the private `nats.conf` (`authorization { token: ... }`) and runtime `.properties`, validates it on restart (`PRE-CONFIG-SECRETS`), and redacts it from all logs (`./pocoma-local logs` and diagnostic bundles).
- **Zero business payload & zero persistence**: notifications carry an empty 0-byte payload on `pocoma.v3.wakeup.<family>.<partition>` with `jetstream: false`, bounded `max_payload: 1024`, and no persistent volume.
- **L8 production boundary**: TLS/mTLS and multi-node NATS clustering remain deferred to L8 multi-VM infrastructure qualification.
