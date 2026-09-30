# POT_E2E — First end-to-end Pot flow

## Goal

First end-to-end Pot flow: async Command → COMMAND_RESULT → authorized READ_POT.

The reference proof crosses the real HTTP endpoints, Command worker, Event worker, ProjectionTask
worker, canonical projection store and Pocoma authorization. Only technical JWT authentication is
faked by Spring Security's test support.

## Delivered slice

- `POST /api/v1/commands` belongs exclusively to `supra-http-write-command`.
- `GET /api/v1/command-results/{commandId}` and
  `GET /api/v1/pots/{potId}?version=V` belong exclusively to `supra-http-read-query`.
- `runtime-web-api` assembles both supras, the shared security boundary and their use cases.
- `ReadPotUseCase` accepts the Pocoma `userId`, token capabilities, `potId` and exact version.
- The read sequence is strictly capabilities → `AUTH@V` → authorization → `READ_POT@V`.
- The exact Pot query currently requires `POT_VIEW` only. It does not require `VIEW_ARCHIVE` and
  does not attempt to classify V as current or historical.
- AUTH membership compares the requesting `userId` with creator/member `userId`, never with a
  `shareholderId`.
- Both projection keys address the same Pot and the same business version.

## Result contract

| Business result | HTTP |
|---|---|
| authorized exact Pot ready | `200` |
| unknown internal identity or forbidden | `404` |
| AUTH absent/not ready/failed | `409 POT_NOT_READY` |
| READ_POT absent/not ready | `409 POT_NOT_READY` |
| READ_POT failed after authorization | `503 POT_PROJECTION_FAILED` |
| invalid version | `400 INVALID_VERSION` |

No WRITE read is used to infer whether a requested version exists.

## Verification

Unit and adapter tests protect short-circuit order, exact projection identities, state mappings and
the WRITE/READ module boundaries. The PostgreSQL/Testcontainers reference test executes:

1. CreatePot over HTTP, then COMMAND_RESULT APPLIED and authorized Pot@1.
2. UpdatePotDetails with `expectedVersion=1`, then COMMAND_RESULT APPLIED and Pot@2.
3. A final exact read of Pot@1 proves that the historic representation is unchanged.

No terminal outcome or projection is inserted manually. SQL is limited to provisioning the external
identity and technical assertions.

The reference Bruno flow lives in `scripts/bruno/00 Pot E2E`. The durable latency audit and its
current SQL proxy are documented in `E2E_Latency_Observability_Audit.md`; no performance
instrumentation is introduced by this step.

The manual runtime proof also exposed a process-lifecycle defect: the generic polling loop used a
daemon thread, so the Event and ProjectionTask JVMs exited as soon as Spring startup returned while
Command stayed alive only because Tomcat owned non-daemon threads. The polling worker now owns a
non-daemon platform thread, and every Spring lifecycle using it defers its shutdown callback until
the active cycle has completed and the waiter has been signalled. Unit lifecycle tests and runtime
process smoke tests protect persistence without adding an HTTP server to Event or ProjectionTask.

## Out of scope

POT_BALANCES, listing/latest queries, pagination, real local OIDC and changes to the AUTH projection
model remain outside this step. `VIEW_ARCHIVE` remains reserved for a later, explicitly bounded
current-versus-historical query contract.
