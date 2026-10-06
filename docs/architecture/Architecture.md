# Architecture — CURRENT after WP6

Baseline architecture: final Modularity re-audit on branch `v2-make-it-pull`. This page describes the delivered runtime chains. The completed work-step archive preserves the [final topology](../steps/completed/ARCHITECTURE/Modularity_Target_Topology.md), [CURRENT→TARGET traceability](../steps/completed/ARCHITECTURE/Modularity_Current_Target_Traceability.md), [WP6 report](../steps/completed/ARCHITECTURE/Modularity_WP6_Execution_Report.md) and [final PASS](../steps/completed/ARCHITECTURE/Modularity_Final_Reaudit.md).

## Command and Pot write

The Web runtime authenticates an ExternalIdentity, admits a durable Command and returns `202`. `runtime-command-consumption-worker` discovers and claims it through generic Consumption, fences the Binding observed at admission, invokes `engine-write-pot`, records a terminal CommandOutcome and appends durable Pot Events. `runtime-command-result-consumption-worker` independently materializes an immutable Command Result owned by the historical ExternalIdentity.

## Registration and Binding

The Web runtime admits a RegistrationRequest. `runtime-registration-consumption-worker` creates the User, acquires the Binding, appends Binding facts and records RegistrationOutcome atomically. `runtime-registration-result-consumption-worker` materializes the immutable historical-owner Result. `runtime-binding-consumption-worker` independently consumes Binding facts through `supra-consume-binding` and `engine-materialize-current-binding`, producing CURRENT_BINDING. Result visibility never depends on the current Binding.

## Exact historical projections

`runtime-event-consumption-worker` consumes durable Pot Events and creates ProjectionTasks for AUTH@V, READ_POT@V and POT_BALANCES@V. `runtime-task-consumption-worker` consumes those tasks, invokes the pure `projector-pot` computations and publishes exact immutable artifacts through `infra-persistence-projection-jdbc`. `engine-read-projection` reads by exact key.

GET Pot resolves authenticated E through `engine-read-current-binding`, obtains U, then reads AUTH@V and READ_POT@V through the exact projection pipeline. It does not fall back to PRIMARY.

## Convergent current-state indexes

CURRENT_BINDING and LKV are specialized C2 **convergent current-state indexes**. Both converge monotonically to the maximum successfully consumed revision/version, accept gaps and ignore stale input. They remain physically separate because their payload and same-rank rules differ.

- CURRENT_BINDING stores ATTACHED/DETACHED state plus provenance and rejects same-revision divergence. It has a real read engine.
- LKV stores `PotId → max successfully consumed PotVersion`. `runtime-latest-known-version-consumption-worker` uses `supra-consume-lkv`, `engine-materialize-latest-known-version`, PRIMARY Event discovery/reload and the READ JDBC max-upsert. It has no read engine because no production consumer needs one.

Neither index uses ProjectionTask. LKV and the exact projection pipeline observe the same durable Event through distinct consumer identities, claims, retries and worker lifecycles.

## Generic Consumption and transactions

`engine-consumption`, `orchestrator-consumption` and `orchestrator-poll-consumption` provide claim, lease, fencing, provenance, retry and polling without concrete business dependencies. Capability supras adapt their own candidate/reload/execution/failure rules. Runtime modules only compose these pieces and technology adapters.

## Infrastructure and runtimes

PRIMARY persistence is `infra-persistence-primary-jpa`. Direct mutable READ stores for CURRENT_BINDING and LKV plus the READ bootstrap live in `infra-persistence-read-jdbc`. Exact projection persistence is `infra-persistence-projection-jdbc`. Transaction integration is `infra-tx-spring`.

The reactor has nine independent distributed runtime composition roots: Web, Command, Command Result, Event, ProjectionTask, Binding, LKV, Registration and Registration Result. The functional-test deployment adds `runtime-monolith`, a thin composition root that imports the reusable Spring composition fragments and the same supras; it is not a business engine or an alternative processing path. No runtime depends on another runtime.

The monolith's exact deployment composition and verification contract are recorded in
[runtime-monolith](runtime-monolith.md). The shared Spring wiring lives in the non-runtime
`composition-runtime-spring` module so distributed runtimes and the functional-test process use
the same worker, HTTP, datasource and lifecycle definitions.

## Documentation status

The modularity migration plan and WP1–WP6 reports are historical execution evidence. `Three_Engine_Families_Revision.md`, old CURRENT audits and earlier module counts are historical design/migration material. This page and the WP6 final topology describe CURRENT.
