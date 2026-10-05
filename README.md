# Pocoma

[![Pocoma CI](https://github.com/kartaguez/pocoma/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/kartaguez/pocoma/actions/workflows/ci.yml)

Pocoma is a Java 21 application for shared pots: participants can record expenses and derive balances. It is also a serious architecture laboratory for asynchronous command processing, versioned domain state, durable workers, and projection-based reads.

Pocoma is not presented as a production-ready financial product. Its purpose is to make difficult consistency and modularity choices explicit, executable, and testable.

## Why Pocoma Exists

A shared-pot domain looks simple until an HTTP request, a business mutation, an event, several projections, and an authorization decision happen in different processes and at different times. Pocoma explores how to keep that system honest:

- accepting work must not be confused with completing it;
- concurrent writers must not silently overwrite newer state;
- a committed mutation must not lose its business event;
- a read must not quietly bypass a lagging projection by consulting the write model;
- retries and worker races must not duplicate authoritative effects;
- identity and authorization evidence must survive asynchronous execution without persisting bearer tokens.

The emphasis is not on architecture labels. It is on the invariants those boundaries enforce and on the failure modes they make visible.

## Architectural Problems

### Admit now, execute later

`POST /api/v1/commands` authenticates the caller, validates the request envelope, persists an immutable command, and returns `202 Accepted` only after that commit. It does not execute Pot business logic. A separate Command runtime later claims, reloads, authorizes, and executes the intent. This keeps HTTP latency and availability separate from distributed mutation work while preserving the submitted intent, authentication evidence, provenance, and eventual outcome.

### Fence concurrent mutations

Pot mutations operate on a global Pot version. Update commands carry an expected version, business preconditions reject stale state, and persistence advances the active version with a compare-and-set. The winning transaction creates exactly the next version; a command based on an obsolete version cannot be applied as though it were current.

### Avoid a fragile dual write

The winning Command transaction contains the primary mutation, terminal outcome, business Event append, provenance, and final claim fencing. If the worker has lost ownership, the final compare-and-set fails and the whole transaction rolls back. Downstream delivery starts from the durable outbox rather than from an after-commit callback.

### Build reads without disguising the write model as READ

Durable Events create versioned `ProjectionTask`s. Projection workers calculate and publish immutable artifacts such as `AUTH@V`, `READ_POT@V`, and `POT_BALANCES@V`. Exact reads request explicit projection identities and versions. They do not silently fall back to PRIMARY when an artifact is absent.

### Expose asynchronous consistency

Projection work is asynchronous, so temporary unavailability is a valid state rather than something to hide. Exact Pot reads distinguish not-ready (`409`) and failed (`503`) projections. An independent latest-known-version consumer records the greatest Event version it has successfully observed, while worker health, polling, and progress metrics support operations. Pocoma does not claim a bounded convergence time, an availability SLA, or an always-ready `CURRENT` Pot view.

### Let workers race safely

Discovery is best effort and may return the same work to several workers. Durable slots, claims, leases, takeovers, and claim-id fencing decide who may commit. Retries and restarts are expected; losing attempts cannot finalize authoritative effects. Event work is also segmented by Pot so every consequence of one Event is assigned to the same segment.

### Keep business logic independent of runtimes

Domain types and policies do not depend on Spring, HTTP, or persistence. Pure projectors calculate artifacts; engines implement use cases; ports describe required capabilities; adapters connect PostgreSQL, Spring transactions, validation, and HTTP; runtime modules are independent composition roots. This makes business behavior testable without a framework and makes technological replacement or runtime extraction an assembly concern.

### Carry identity across the asynchronous boundary

Spring Security's OAuth2 Resource Server validates the bearer token at admission. Pocoma persists the attested external identity, the presented Binding identity, bounded authentication evidence, and external capabilities—not the raw token. The Command worker re-resolves and fences the exact external-identity/Binding occurrence before committing business work. Read-side authorization uses explicit versioned `AUTH` artifacts; result ownership remains tied to the historical external identity captured for that result.

## Core Guarantees

The precise scope, evidence, and limits of each statement are maintained in [System Guarantees](docs/guarantees/System_Guarantees.md). In summary:

- accepted Commands and Registration Requests are durable and immutable before asynchronous execution;
- the winning Command execution commits its state change, outcome, business Event, provenance, and ownership fence atomically;
- stale Pot writes are rejected through expected-version checks and an active-version compare-and-set;
- exact projection reads do not silently fall back to the write model or another version;
- missing, failed, and lagging asynchronous work remains explicit; no maximum convergence time is promised;
- durable claims, retries, takeovers, and fencing make worker execution restart-safe for the tested scenarios;
- exact projection identities include projection type, target identity, and target version, and published artifacts are immutable;
- result ownership is evaluated against the historical external identity rather than today's Binding.

These are bounded engineering guarantees, not claims of universal correctness. The canonical document separates properties guaranteed by code/schema, scenarios proven by tests, measured behavior, targets, and unspecified behavior.

## How the System Works

```text
OAuth2 client
    │
    ▼
Web runtime ── commit immutable Command ──► PostgreSQL PRIMARY
    │                                             │
    └── 202 Accepted                              ▼
                                      Command worker claims + fences
                                                  │
                                      state + outcome + Event
                                           one transaction
                                                  │
                         ┌────────────────────────┴───────────────────────┐
                         ▼                                                ▼
              Event worker creates                            Result consumers and
              versioned ProjectionTasks                       current-state indexes
                         │
                         ▼
              Projection worker publishes
              immutable AUTH/READ/BALANCE @ V
                         │
                         ▼
              exact, authorization-aware reads
```

The reactor currently has nine independent runtime composition roots: Web, Command, Command Result, Event, ProjectionTask, Binding, Latest Known Version, Registration, and Registration Result. No runtime module depends on another runtime module; coordination happens through durable state and explicit contracts.

## Architecture

The codebase follows dependency direction rather than framework ownership:

```text
domain → engines/projectors → ports → adapters/supras → runtime composition roots
```

- **Domain** owns business values, policies, version concepts, and generic Consumption primitives.
- **Engines and projectors** implement business use cases and pure projection calculations.
- **Ports** state what an engine needs without choosing a technology.
- **Adapters and supras** bind PostgreSQL, transactions, HTTP, authentication, and worker-specific recipes.
- **Runtimes** assemble one deployable capability and activate only its own loop or API.

See the canonical [Architecture](docs/architecture/Architecture.md) for the delivered chains and [module dependency matrix](docs/architecture/module-dependency-matrix.md) for ownership detail.

## Running Locally

Prerequisites:

- Java 21;
- Docker for PostgreSQL and integration tests;
- an OAuth2/OIDC issuer when exercising authenticated HTTP endpoints.

Start the local PostgreSQL service:

```bash
cd app
docker compose -f docker-compose.postgres.yml up -d
```

Pocoma is composed of independent runtimes rather than one required monolith. Use the [runtime start commands](docs/operations/cmd-start-runtimes.md) for the Web, Command, Event, and ProjectionTask processes, and enable the additional consumers needed by the scenario being exercised.

## Validation

The Maven Wrapper is the supported entry point. The full command used by CI is:

```bash
cd app
./mvnw --batch-mode --no-transfer-progress test
```

The reactor includes module tests, PostgreSQL integration tests through Testcontainers, and global architecture tests. During development, use the smallest canonical verification slice that proves the change; the normative [Reactor Verification Policy](docs/testing/Reactor_Verification_Policy.md) defines those slices and the conditions for escalating to a global proof. See [Continuous Integration](docs/development/ci.md) for the permanent CI contract.

## Observability

Worker runtimes expose Spring Boot Actuator health, liveness, readiness, and Prometheus endpoints. Bounded-cardinality metrics cover polling cycles, candidates, executions, duration, running state, and selected capability progress. Durable slots, claims, failures, outcomes, projection failures, and latest-known-version watermarks retain the operational state that metrics deliberately do not duplicate.

See the [Command runtime](docs/architecture/command-consumption-runtime.md) and [transactional Consumption model](docs/architecture/consumption-transactional-execution.md) for the concrete semantics and limits.

## Documentation

The [documentation index](docs/README.md) is the canonical map of `CURRENT`, `TARGET`, historical, and superseded material.

- **System behavior and guarantees:** [Functional Model](docs/product/Functional_Model.md), [Architecture](docs/architecture/Architecture.md), and [System Guarantees](docs/guarantees/System_Guarantees.md).
- **Write-side model:** [durable Command admission](docs/architecture/recorded-command-intake.md), [Command persistence](docs/architecture/recorded-command-persistence.md), and [write-side closure](docs/architecture/write-side-closure.md).
- **Consumption architecture:** [transactional execution](docs/architecture/consumption-transactional-execution.md), [Command runtime](docs/architecture/command-consumption-runtime.md), and [Event runtime](docs/architecture/consumption-event-pull-runtime.md).
- **Read and projection architecture:** [projection engine](docs/architecture/projection-engine.md), [ProjectionTask execution](docs/architecture/projection-task-execution.md), and [exact-read target](docs/architecture/read-side-target.md). The documentation index identifies which specialized documents are delivered descriptions and which remain target references.
- **Active design work and debt:** [technical debt registry](docs/debts/README.md). Completed plans and archived designs remain evidence, not current specifications.

## Project Status

Pocoma is under active architectural development and is primarily maintained by one person. The repository contains substantial executable proofs, but its APIs, deployment model, migrations, and operational practices may still change. There is no published SLA/SLO, compatibility promise, or claim that the application is ready to hold real financial data in production.

## Contributing

Contributions are welcome when they are small, focused, tested, and consistent with the documented boundaries. Start with [CONTRIBUTING.md](CONTRIBUTING.md) and follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Security

Please do not disclose an exploitable vulnerability in a public issue. Follow [SECURITY.md](SECURITY.md) for private reporting or for requesting a private contact channel without publishing sensitive details.

## License

Pocoma is licensed under the [Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for attribution information.
