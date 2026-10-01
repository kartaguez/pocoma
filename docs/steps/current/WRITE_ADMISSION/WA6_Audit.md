# WA.6 — Audit READ identity binding et ownership exact de `COMMAND_RESULT`

```text
Audit date: 2026-10-01
Scope: repository state at bbb794cf7eaf533bc8ca1710a3dd0c8f400ce94a
Mode: audit only; no WA.6 implementation
Authority: Step_Canon.md, especially WA4, WA6, WA9 and WA10
```

## 1. Executive conclusion

WA.6 is not an assembly-only lot. The exact V2 ExternalIdentity is already durably captured and can
be propagated to `COMMAND_RESULT` without ambiguity, but the identity-binding half is missing two
prerequisites:

1. there are no production `ExternalIdentityAttached` / `ExternalIdentityDetached` facts, nor any
   equivalent durable mutation log;
2. there is no durable monotone order for binding mutations.

The current `external_identities` row is the sole authority for the current relation
`(issuer,subject) -> (user_id,binding_id)`. Its exact conditional delete already protects the WRITE
authority from stale detach, but that protection is not transferable to an asynchronous projector:
after the row is deleted, the primary store retains neither a tombstone nor a revision.

The smallest coherent extension is a durable per-ExternalIdentity binding stream revision, allocated
inside the same transaction as every successful acquire/detach, plus immutable Attached/Detached
facts carrying that revision as source metadata. `bindingId` remains opaque and is never compared for
order. A dedicated current READ table then applies only a strictly greater revision and retains a
tombstone on detach. This reuses the monotone conditional-upsert pattern already implemented by
`JdbcLatestKnownVersionAdapter`; the immutable exact projection store is not by itself suitable for
a mutable `CURRENT_BINDING` view.

`COMMAND_RESULT` has a separate, immediately actionable defect: its projection input loader still
requires `recorded_commands.auth_user_id`. New Commands are V2 and have that column `NULL`, so the
current chain cannot materialize their result. Exact ownership is available directly from V2
`auth_issuer + auth_subject`; it must be copied into the READ artifact and compared with the E obtained
from the authenticated principal. No lookup of the current User or binding is required or allowed.

V1 cannot be converted to exact-E: it stores an issuer and a User id, but no subject. V1 must remain
explicitly isolated until drained/expired; it must never be assigned to an identity inferred from the
User's current bindings.

## 2. Git baseline

| Item | Observed value |
|---|---|
| Current branch | `v2-make-it-pull` |
| Local HEAD | `bbb794cf7eaf533bc8ca1710a3dd0c8f400ce94a` |
| `origin/v2-make-it-pull` | `bbb794cf7eaf533bc8ca1710a3dd0c8f400ce94a` |
| Divergence | ahead `0`, behind `0` |
| Initial working tree | clean |

Structuring commits relevant to WA.1–WA.5:

| Commit | Contribution |
|---|---|
| `fd08cd28` | canonical User/ExternalIdentity/BindingId ownership and neutral AuthN contracts |
| `3a8a27ca` | V18 User/binding persistence, exact binding port, locking and stale-detach proofs |
| `33f207b2` | V19 dual V1/V2 RecordedCommand envelopes |
| `8f65eab2` | V2 worker resolution `(E,B)->U` under the business transaction |
| `bbb794cf` | HTTP admission produces V2 only, without primary identity lookup |

The audit created only this report. Generated Maven `target/` content is ignored.

## 3. Current User / ExternalIdentity / Binding authority

### 3.1 Schema and migrations

- V9, `infra-persistence-jpa/.../V9__external_identities.sql`, created
  `external_identities(issuer,subject,pocoma_user_id)` with primary key `(issuer,subject)`.
- V18, `.../V18__user_identity_binding_authority.sql`, created `users(user_id UUID PK)`, backfilled
  all distinct Users, generated one random UUID `binding_id` per existing row, renamed
  `pocoma_user_id` to `user_id`, made `binding_id` non-null and globally unique, added the User FK
  and an index on `user_id`.
- V19, `.../V19__recorded_command_envelope_expand.sql`, is Command persistence, not binding history.
  It adds the V2 command fields including `auth_subject` and `binding_id`.

There is no identity-binding event/outbox table, no identity stream table, no binding revision and no
binding tombstone.

### 3.2 Domain objects and ports

`domain-user-identity` owns:

- `User(PocomaUserId)`;
- `ExternalIdentity(issuer,subject)`, with exact value equality;
- `BindingId(UUID)`, documented and tested as opaque, with no ordering contract;
- `BindingAcquireResult { ACQUIRED, CONFLICT }`;
- `BindingDetachResult { DETACHED, NOT_CURRENT }`;
- `UserAuthorityPort.create/findById`;
- `ExternalIdentityBindingPort.findUserId(E,B)`, `lockCurrentBinding(E,B)`,
  `acquire(E,U,B)`, `detach(E,B)`;
- legacy `ExternalIdentityResolverPort.findUserId(E)`.

There are no attach/detach application use cases yet. Production implementations are the ports and
their persistence adapters; current acquire/detach callers outside tests do not exist. Future
Registration is planned to become the first production writer.

### 3.3 Adapters and exact behavior

`JpaUserAuthorityAdapter` / `UserJdbcRepository` own minimal User persistence.
`JpaExternalIdentityBindingAdapter` / `ExternalIdentityJdbcRepository` implement binding authority:

- `findUserId(E,B)` is an exact equality query;
- `lockCurrentBinding(E,B)` uses the same query with `FOR UPDATE`;
- `acquire` inserts the current row and uses `ON CONFLICT (issuer,subject) DO NOTHING`;
- `detach` deletes only `WHERE issuer=? AND subject=? AND binding_id=?`;
- all port operations require the caller's transaction (`MANDATORY`).

The legacy resolver queries only `(issuer,subject)` and returns `user_id`. It is still used by READ
controllers and is explicitly marked for removal at WRITE_ADMISSION cutover.

### 3.4 Meaning and lifecycle of `bindingId`

`bindingId` identifies one exact occurrence of attaching E to U. It is globally unique at the
database level, opaque, non-ordinal, and absent after detach because the row is deleted. A reattach,
including to the same User, must use a new B. A stale `detach(E,B1)` cannot delete current B2 because
the delete predicate includes B.

The authoritative answer to “E -> current U + current B” is therefore exactly the live row in
`public.external_identities`, backed by its PK, unique B constraint and User FK. There is currently no
READ projection or READ port that returns this pair.

### 3.5 Attached/Detached facts

They do **not** exist in production code. The strings `ExternalIdentityAttached` and the proposed
detach fact occur only in planning/canon documentation. They are absent from:

- `domain-user-identity` event types;
- primary migrations and outbox tables;
- event discovery and `PocomaProjectionMaterializationPolicy`;
- projection producers and runtimes.

The existing Pot business-event outbox and `command_terminal_events` are not equivalent: neither
records identity-binding mutations.

## 4. Target self-service projection

### 4.1 Recommended model

Use a dedicated current read model, conceptually:

```text
pocoma_read.current_identity_binding
  issuer            text
  subject           text
  user_id           uuid null
  binding_id        uuid null
  active            boolean
  source_revision   bigint
  source_event_id   uuid
  primary key (issuer, subject)
```

The row is a projection, not a second business authority. `active=true` requires U and B;
`active=false` is a tombstone and must retain the last source revision (and may retain the detached B
for diagnostics/invariant checks, without exposing it over HTTP). The GET adapter returns only active
rows.

The natural key is the exact tuple `(issuer,subject)`. Do not concatenate it into an ambiguous
string unless a length-prefixed or canonical JSON encoding is used. At the SQL boundary, two columns
are simpler and preserve exact equality.

### 4.2 Why not the existing immutable artifact store alone

`pocoma_read.projection_root` is keyed by
`(projection_type,target_object_type,target_object_id,target_version)` and publication is immutable:
an existing key returns `ALREADY_EXISTS`. It is ideal for exact historical Pot projections and the
one-shot command result, but it has no canonical “head” selection and no replacement semantics.
ProjectionTask completion can also occur out of source order. Encoding every binding revision as an
exact projection would still require a durable head/watermark and a current lookup.

The relevant reusable artifact is instead the monotone current-state pattern in
`source_version_watermarks` / `JdbcLatestKnownVersionAdapter`: conditional upsert only when the
candidate version is greater. `CURRENT_BINDING` should use that pattern with an identity-specific
revision and keep a detach tombstone.

### 4.3 Producer runtime

Prefer a dedicated identity-binding fact consumer, structurally similar to the direct
LatestKnownVersion consumer:

```text
identity_binding_facts -> generic Consumption claim/fencing
                       -> apply fact by source_revision
                       -> pocoma_read.current_identity_binding
```

It may reuse generic polling, claims, leases, retry and fencing. It should not route through the
Pot-specific historical projector, and the Command worker must never consume this READ model.

Apply rules:

- `Attached(E,U,B,revision)` upserts active U+B only when `revision > stored.source_revision`;
- `Detached(E,B,revision)` upserts an inactive tombstone only under the same greater-than guard;
- equal revision is an idempotent replay and must be content-identical, otherwise an invariant
  violation;
- lower revision is a stale replay and has no effect;
- B is checked for equality/consistency only, never used as order.

## 5. Occurrence awareness and ordering

### 5.1 What exists today

There is no durable order usable for identity-binding projection.

- `bindingId` is intentionally unordered.
- `external_identities` stores only the current row.
- timestamps are absent from the binding table.
- the generic Event discovery tuple `(recorded_at,event_id,projection_type)` is a discovery cursor,
  not causal ordering and not completion serialization.
- Pot `version` orders Pot aggregate facts only; it does not apply to identity bindings.
- `projection_tasks.target_version` identifies exact input and documentation explicitly says it is
  not an ordering/serialization constraint.
- database sequence/UUID identifiers elsewhere do not encode per-E binding causality.

The WRITE store is nevertheless safe against stale detach because the delete includes B. This is a
conditional mutation guarantee, not replayable history.

### 5.2 Smallest required extension

Add a durable stream row keyed by E, for example:

```text
identity_binding_stream(issuer, subject, current_revision bigint, primary key (...))
```

Every successful acquire/detach must, in the same transaction:

1. lock/create the stream row;
2. advance `current_revision` by one;
3. mutate the current authority conditionally;
4. append exactly one immutable fact containing E, U+B for attach or E+B for detach, plus the
   allocated revision and a unique event id.

The stream row survives detach. This gives a durable per-E causal order and avoids relying on global
sequence allocation or commit timestamps. Failed/conflicting/stale mutations publish no fact and do
not advance the revision.

For `Attached(B1@1)`, `Detached(B1@2)`, `Attached(B2@3)`, late delivery of either revision 1 or 2 is
ignored after revision 3. This remains true under duplicate delivery, task takeover and out-of-order
worker completion.

This is sufficient for idempotence only when fact append and authority mutation are atomic and READ
apply uses a conditional greater-than update. Generic Consumption then supplies processing fencing;
the revision supplies semantic stale-message protection.

## 6. Bootstrap of V18 bindings

V18 bindings predate any future facts, so replay cannot initialize them. Creating historical
Attached facts would be false history and is rejected.

Recommended sequence:

1. install the stream/fact schema and initialize one stream row at revision `0` for each current V18
   E, without emitting a fact;
2. make every future binding writer emit ordered facts atomically;
3. create the READ table;
4. run a dedicated bounded keyset bootstrap over authoritative
   `(issuer,subject,user_id,binding_id,current_revision)` snapshots;
5. upsert the READ row only when the snapshot revision is greater than or equal to the stored one,
   with strict same-revision content validation;
6. let real facts with revisions `> 0` converge any mutation racing with the scan;
7. record progress/cursor and counts so reruns are safe and reproducible.

This is idempotent, bounded by page size, restartable, loses no live binding and imposes no order on
B. Direct bootstrap access to the WRITE authority is confined to this administrative projection
initialization path; the serving GET never has such access.

A one-shot cross-schema SQL migration is possible in today's single-DataSource deployments, and is
simple for clean bootstrap. It is not preferred because it is not operationally page-bounded and
couples the READ migration to physical co-location with `public.external_identities`. Event replay
alone is impossible because the relevant events do not exist.

## 7. Current `COMMAND_RESULT` chain

### 7.1 Durable source and projection

1. `JdbcCommandOutcomeAdapter.publish` writes `command_outcomes` and one
   `command_terminal_events` row in the Command transaction. The outcome stores command id, result
   shape and time, but no owner.
2. Event discovery unions `command_terminal_events` with the Pot outbox. The policy maps all three
   terminal Command event types to `COMMAND_RESULT`.
3. `JdbcProjectionTaskStoreAdapter.ensure` creates a unique task for
   `COMMAND_RESULT / COMMAND / commandId / version 1`.
4. `JdbcCommandResultProjectionInputLoader` loads the outcome and separately selects
   `recorded_commands.auth_user_id` as ownership.
5. `CommandResultProjector` writes one immutable artifact containing `submittedByUserId`.
6. `GetCommandResultService` reads the exact projection and returns NotFound unless the supplied
   User id equals `submittedByUserId`.
7. `CommandResultController` exposes `GET /api/v1/command-results/{commandId}`, resolves E to the
   current User through the primary legacy resolver inside a transaction, then calls the service.

Current ownership is therefore User-based, sourced indirectly from the RecordedCommand, and access
also depends on E's *current* User binding. It is not exact-E ownership. E2 attached to the same U as
E1 can see E1's result; later rebinding can transfer or remove visibility.

### 7.2 V1 and V2 paths

- V1 rows have `envelope_version=1`, `auth_user_id`, `auth_issuer`, permissions and times. They have
  no subject and no binding id.
- V2 rows have `envelope_version=2`, exact `auth_issuer`, `auth_subject`, `binding_id` and external
  AuthN evidence. `auth_user_id` is required to be NULL.
- Worker execution is explicitly switched by envelope type: V1 uses its stored AuthorizationSnapshot;
  V2 locks and resolves exact `(E,B)`.
- Both produce the same owner-less `command_outcomes` and terminal-event shape.

The current result loader is V1-only in practice. For V2 its `queryForObject(... auth_user_id ...)`
returns null and the explicit `requireNonNull` fails. The existing `CommandResultProjectionChainPostgresTest`
seeds a V1 row. The first `CommandCompletionE2EPostgresTest` also asserts three V1 rows. Its V2 HTTP
test verifies command execution and Pot READ but does not assert a V2 command result after the
pipeline. Thus the green suite does not prove V2 `COMMAND_RESULT`.

## 8. Exact-E target for `COMMAND_RESULT`

For V2, the unambiguous owner is already present at admission and at rest:

```text
TargetCommandEnvelope.externalIdentity
  -> recorded_commands.auth_issuer + auth_subject
  -> command-result projection input
  -> READ artifact ownerExternalIdentity
  -> compare with AuthenticatedExternalPrincipal.identity()
```

Required changes:

- make the projection loader branch on `envelope_version` rather than nullability;
- for V2 load exact issuer+subject, never resolve a User and never consult current binding;
- store exact owner E in the artifact (two fields, or a canonical unambiguous derived key);
- change the V2 read use case to accept `ExternalIdentity`, not `UUID requestingUserId`;
- make the controller pass only `principal.identity()` and remove
  `ExternalIdentityResolverPort`/`TransactionRunner` from this GET path;
- return the same non-oracle 404 for absent, not-ready, wrong owner, legacy-not-exposed and invalid
  projection states.

Detach/rebind after submission has no effect because the immutable submission E is the owner. B is
not part of result visibility.

Because V1 and V2 ownership schemas are semantically incompatible, the safest expand path is a
parallel exact-E projection generation/type (for example internal `COMMAND_RESULT_V2`) routed from
the retained terminal events, with scheduling explicitly restricted to
`recorded_commands.envelope_version=2`. The current policy is unconditional by event type, so this
eligibility predicate (or a dedicated V2 backfill locator using the same real terminal events) is a
required extension; blindly routing the new type would create impossible exact-E tasks for V1.
A new projection type gives eligible existing terminal events a new generic Consumption identity,
so historical V2 outcomes are rediscovered without resetting completed V1 materializations. The
legacy `COMMAND_RESULT` artifact can remain untouched until WA.7. Reusing the same immutable key
would require repair/reset of already failed V2 projection consumptions and a schema capable of
interpreting old User-owned artifacts; it has higher migration risk.

## 9. Legacy V1 assessment

| Question | Finding |
|---|---|
| Exact `(issuer,subject)` reconstructible? | No. V1 stores issuer but not subject. |
| Persisted V1 results possible? | Yes. V1 commands, outcomes, terminal events and immutable User-owned READ artifacts are all supported and tested. |
| Consumable V1 backlog possible? | Yes. V1 model/mapper/worker branches remain active; no repository precondition proves backlog zero. |
| Reliably distinguishable? | Yes, `recorded_commands.envelope_version` is constrained to 1 or 2 and the Java envelope is sealed/discriminated. |
| Exact-E ownership migratable heuristically? | No. Current User bindings are neither historical proof nor a valid substitute. |

Preflight must separately count at least:

- unterminalized V1 Commands;
- terminalized V1 outcomes not yet projected;
- existing V1 User-owned projections;
- failed/pending projection consumptions for V2 under the old type.

Recommendation: keep V1 on an explicitly legacy, time-bounded read path only if product retention
requires continued access; otherwise prevent exact-E cutover until the consumable V1 backlog is zero
and accept that retained V1 results cannot be exposed through the exact-E endpoint. Never migrate
their ownership by enumerating the current identities of `auth_user_id`.

## 10. HTTP self-service contract

Current relevant endpoints:

- no current-user or current-binding GET exists;
- `GET /api/v1/command-results/{commandId}` exists and performs forbidden primary E->U resolution;
- `GET /api/v1/pots/{potId}?version=...` has the same legacy primary resolver debt, although Pot
  serving is outside the minimal WA.6 implementation;
- `POST /api/v1/commands` correctly obtains E only from AuthN and B from the body.

Minimal target contracts:

```http
GET /api/v1/me/binding
Authorization: Bearer ...

200 { "userId": "uuid", "bindingId": "uuid" }
404 when READ has no active binding for authenticated E
```

No issuer, subject or User selector is accepted. E is exclusively
`AuthenticatedExternalPrincipal.identity()`.

```http
GET /api/v1/command-results/{commandId}
Authorization: Bearer ...

200 existing result body when projection.ownerE == authenticated E
404 otherwise
```

The V2 controller passes commandId plus authenticated E to a READ-only use case. It neither resolves
current U nor accepts a client-provided E. If a temporary legacy endpoint is retained, it must be
separate and explicitly removed by WA.7; it cannot claim exact-E semantics.

## 11. Module boundaries and architecture tests

Recommended ownership:

- `domain-user-identity`: fact contracts and revision value semantics only; no Spring/SQL/runtime;
- a focused identity READ engine module (new or carefully scoped existing engine): apply/query use
  cases and ports for current binding;
- `infra-persistence-jpa`: primary stream/fact append and discovery adapters;
- `infra-read-persistence`: current-binding table migration and monotone apply/query adapter;
- locator/orchestrator/runtime: direct fact consumption using generic Consumption;
- `engine-command-result`: V2 projection schema/projector and exact-E visibility policy;
- `supra-http-read-query`: self-service controllers receiving the authenticated principal;
- `runtime-web-api`: composition only.

The GET path must not depend on `ExternalIdentityBindingPort`, `ExternalIdentityResolverPort`,
`ExternalIdentityJdbcRepository`, `command_outcomes`, `recorded_commands` or a primary JdbcTemplate.
Projection workers may load their declared durable sources; HTTP may not. No User/Identity business
authority is duplicated in READ: current binding remains derived and eventually consistent.

Existing tests to extend:

- `HexagonalArchitectureTest`: exact-E Command result contract; no primary resolver/transaction in
  the two GET controllers; no READ dependency from Command worker; ownership of new ports/facts;
- `Pcl4LegacyQueryReadAbsenceTest`: preserve absence of legacy Query/store fallbacks;
- `CommandResultProjectionChainPostgresTest`: add V2 exact-E chain and E1/E2 same-U denial;
- `CommandCompletionE2EPostgresTest`: prove V2 HTTP Command -> outcome -> exact-E result and remove
  primary identity lookup from result GET;
- `PrimaryMigrationsPostgresTest`: stream/facts, upgrade and bootstrap prerequisites;
- `Pcl8DatabaseDemolitionPostgresTest` / checksum guards: append migrations only, preserve final
  schema equivalence;
- add dedicated current-binding adapter/runtime PostgreSQL tests and HTTP resource-server tests.

## 12. Atomic WA.6 implementation plan

### WA.6.1 — Canonical binding facts and source revision

- Likely files/modules: `domain-user-identity`; new primary migration after V19;
  `ExternalIdentityBindingPort`; `JpaExternalIdentityBindingAdapter` and repository.
- Change: add `ExternalIdentityAttached(E,U,B)` and `ExternalIdentityDetached(E,B)` contracts plus
  source metadata `{eventId, revision, recordedAt}`; add durable per-E stream and immutable fact
  table; successful authority mutation and fact append share one transaction.
- Invariant: exactly one ordered fact per successful mutation; none for conflict/stale detach.
- Proof: unit value tests; migration constraints; PostgreSQL rollback, concurrent acquire,
  detach/reattach and atomicity tests.
- Depends on: none; prerequisite for all current-binding runtime work.

### WA.6.2 — Current-binding READ model and monotone apply

- Likely modules: focused identity READ engine contracts; `infra-read-persistence` V9+ migration and
  adapter.
- Change: current/tombstone table keyed by `(issuer,subject)`; apply only strictly newer revision;
  exact same-revision replay accepted only if identical.
- Invariant: B1 attach/detach followed by B2 attach stays B2 under any replay order.
- Proof: unit transition matrix and Testcontainers permutations including duplicates, stale detach,
  out-of-order completion and divergent duplicate rejection.
- Depends on: WA.6.1 revision contract.

### WA.6.3 — Binding fact consumption runtime

- Likely modules: new identity-binding locator/runtime or extensions mirroring
  `locator-consumption-latest-known-version`; generic `orchestrator-consumption` and
  `supra-consumption-worker` reused.
- Change: bounded discovery, stable Consumption key by fact id, fenced apply to READ.
- Invariant: restart/takeover/multi-worker delivery converges without semantic regression.
- Proof: runtime PostgreSQL tests for retry, claim loss, restart, two workers and late old facts.
- Depends on: WA.6.1–2.

### WA.6.4 — Existing-binding bootstrap

- Likely modules/files: a dedicated administrative bootstrap component/runbook; primary stream
  initialization migration; READ adapter batch API; operations SQL for preflight/validation.
- Change: keyset-page current authority snapshots with their stream revision into READ; persist
  cursor/counts; no event emission.
- Invariant: every V18 live binding appears once or is superseded by a real later fact; rerun is safe.
- Proof: non-empty V18 upgrade, mutation racing each page boundary, interruption/resume, clean
  bootstrap and final set equality.
- Depends on: WA.6.1–3 deployed in expand order.

### WA.6.5 — V2 `COMMAND_RESULT` exact-E generation

- Likely files: `CommandResultProjectionInput`, definition, projector, loader; materialization
  policy/runtime catalog; read-store projection tests.
- Change: add a parallel exact-E projection type/generation populated from V2
  `auth_issuer/auth_subject`; make task discovery/backfill eligible only for `envelope_version=2`;
  retain old V1 artifact unchanged.
- Invariant: result ownership is immutable submission E, independent of U/B after submission.
- Proof: applied/rejected/failed V2 cases; E2 same U denied; detach/rebind unchanged; old terminal
  events backfill the new projection idempotently.
- Depends on: recorded command V2 already present; independent of binding projection.

### WA.6.6 — Explicit V1 treatment and cutover gate

- Likely files: operations preflight SQL/runbook, projection policy/config, possibly a separate
  legacy use case/controller retained temporarily.
- Change: inventory V1 backlog/results; drain processable V1 commands; choose bounded legacy result
  retention or deliberate inaccessibility; prohibit exact-E migration.
- Invariant: no V1 result is falsely assigned to E.
- Proof: counts/gates, mixed V1/V2 tests, V1 subject absence, exact-E endpoint returns 404 for V1.
- Depends on: product decision in section 14 and WA.6.5.

### WA.6.7 — Self-service GETs

- Likely files: new identity read use case/service; `CommandResultController`,
  `GetCommandResultUseCase/Service`; new binding controller/DTO; `CommandResultReadConfiguration` and
  Web runtime composition.
- Change: E exclusively from `AuthenticatedExternalPrincipal`; binding GET reads current-binding
  READ; result GET compares exact owner E.
- Invariant: no arbitrary identity lookup, no E1/E2 traversal through common U, no primary read.
- Proof: controller unit tests and full JWT HTTP tests for owner/non-owner/unknown/detached/not-ready.
- Depends on: WA.6.2, WA.6.4, WA.6.5 and V1 policy.

### WA.6.8 — Architecture and regression closure

- Likely files: `HexagonalArchitectureTest`, PCL absence/demolition tests, runtime composition tests,
  README/runtime projection type lists and operations docs.
- Change: guards for core/app/infra/api/worker directions; forbid identity primary dependencies in
  HTTP READ; ensure Command worker cannot consume current-binding READ; preserve append-only
  migrations.
- Invariant: READ is derived, WRITE authority unique, no circular/module/runtime coupling.
- Proof: targeted unit suite, primary/read Testcontainers, full HTTP E2E, restart/multi-worker,
  migration upgrade/clean equivalence, `git diff --check`, then full Maven reactor.
- Depends on: all earlier substeps.

## 13. Validation executed during the audit

No source/test file was changed. The following existing suites passed:

1. `./mvnw -pl domain-user-identity,engine-command,engine-command-result,supra-http-read-query -am test`
   — 20-module targeted reactor, all green.
2. `./mvnw -pl infra-persistence-jpa,architecture-tests -am \
   -Dtest=JpaUserIdentityAuthorityAdapterPostgresTest,JpaRecordedCommandAdapterPostgresTest,\
PrimaryMigrationsPostgresTest,CommandResultProjectionChainPostgresTest,\
CommandCompletionE2EPostgresTest,HexagonalArchitectureTest,Pcl4LegacyQueryReadAbsenceTest \
   -Dsurefire.failIfNoSpecifiedTests=false test`
   — PostgreSQL 17/Testcontainers, HTTP/E2E and 51 architecture guards; all green.

The second run reported 27 selected infra tests and 58 selected architecture/E2E tests. Green
status validates the observed baseline only; as explained above, its Command-result projection
fixtures are V1 and do not prove V2 result materialization.

## 14. True blocking question

The repository and canon do not define the product retention policy for already persisted V1
results. One decision is required before HTTP cutover:

> Must historical V1 results remain readable for a bounded compatibility period, or may the new
> exact-E endpoint deliberately return 404 for them once the processable V1 backlog is drained?

If they must remain readable, keep a separately named/secured legacy User-owned endpoint until its
declared expiry. It must never be presented as exact-E. No other architecture question blocks the
recommended implementation: the per-E source revision is implementation metadata compatible with
the existing canon.

## 15. Required YES / NO / PARTIAL conclusions

| Statement | Verdict | Short justification |
|---|---|---|
| Attached/Detached facts already exist | **NO** | Documentation only; no domain type, table, outbox, policy or runtime. |
| Current binding READ model already exists | **NO** | Only the primary `external_identities` current row exists. |
| Exact E available on V2 Command | **YES** | `TargetCommandEnvelope` and V19 persist exact issuer+subject. |
| Exact E reconstructible on V1 Command | **NO** | V1 has issuer and User id but no subject. |
| Durable ordering available for binding projection | **NO** | No binding revision/position/history; discovery order is not causal order. |
| Existing bindings can be bootstrapped without fake events | **YES** | Bounded direct snapshot from the authority into READ, with revision-0 stream baseline. |
| COMMAND_RESULT currently owned by exact E | **NO** | Artifact and GET authorization use `submittedByUserId` plus current E->U lookup. |
| COMMAND_RESULT can be migrated to exact E without touching legacy V1 | **PARTIAL** | V2 can use a parallel exact-E projection from retained terminal events; V1 cannot be converted and needs an explicit isolation/retention policy. |
| WA.6 requires canon change | **NO** | Canon already requires exact-E ownership, occurrence-aware facts and READ-only GETs; revision is required implementation metadata. |
| WA.6 can be implemented without direct READ access to WRITE authority | **YES** | Runtime GETs read only READ; facts feed projection. Only the bounded administrative bootstrap snapshots WRITE. |
