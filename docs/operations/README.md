# Operations

This section covers diagnostics, status tracking, and recovery for local Pocoma instances:

- **[Troubleshooting & Diagnostics](troubleshooting.md)** — interpreting states (`operationalState` / `qualificationState`), error codes (`PRE-*`, `DB-*`, `INCOMPATIBLE-*`, `KEYCLOAK_*`, `GRAFANA-*`), and known limits of the local distribution.
- **[Crash Recovery & Lifecycle](recovery.md)** — graceful restart, ungraceful stop recovery (`270 s`), PostgreSQL outage recovery (`draining` / admission capacity), `reset`, `destroy`, and `upgrade-grafana`.
