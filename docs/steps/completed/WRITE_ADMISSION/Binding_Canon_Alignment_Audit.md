# WRITE_ADMISSION — Binding canon alignment audit

```text
Scope: implementation delivered in WA.1–WA.6, at ecdb603d77a84bef7886086c6a0e8ed5d0a90310
Normative authority: docs/steps/current/WRITE_ADMISSION/Step_Canon.md (WA1–WA11)
Method: repository/code/schema/test inspection and targeted PostgreSQL tests; no implementation change
```

## Baseline and evidence

At start, `git branch --show-current` returned `v2-make-it-pull`; `git rev-parse HEAD` returned
`ecdb603d77a84bef7886086c6a0e8ed5d0a90310` (`docs: close binding command fencing canon`);
`git rev-list --left-right --count origin/v2-make-it-pull...HEAD` returned `0 0`; and
`git status --short` was empty. This is the local tracking ref comparison, not a remote fetch.

The following command was run from `app/` without editing the repository:

```text
./mvnw -pl infra-persistence-jpa,infra-read-persistence,runtime-binding-consumption-worker,runtime-web-api -am -Dtest=JpaUserIdentityAuthorityAdapterPostgresTest,ExternalIdentityBindingLifecycleMigrationPostgresTest,CurrentBindingPersistencePostgresTest,BindingRuntimePostgresTest,CommandConsumptionPostgresTest,CommandAdmissionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test -q
```

Exit code: `0`. Surefire reports record 12, 2, 2, 7, 18 and 5 tests respectively, all with zero
failure/error (46 targeted tests). A green test demonstrates its exercised path only. The
counterexamples below follow from SQL constraints and adapter behavior that these tests do not
exercise.

Classification is the strongest applicable dimension for each requirement (`MIGRATION`/`CODE` >
`TEST` > `DOC` > `NONE`). `MIGRATION` denotes a durable data/schema mismatch even where a Java
change would accompany its correction. `Remediation required` is stated for each gap; no
implementation plan is prescribed here.

## Main alignment matrix

| ID | Canonical requirement | Current implementation | Evidence | Classification | Impact |
|---|---|---|---|---|---|
| A1 | B identifies an exact, opaque, unique, never reused occurrence; attach/reattach, including to the same U, gets a new B; equality alone matters. | `BindingId` is a UUID value object; resolution and detach compare exact E+B. V18 makes B unique only among **active** rows. `acquire(E,U,B)` accepts a caller supplied B; after detach deletes the active row, neither V20 facts nor the stream has a unique constraint on historical B. The same B can therefore be acquired again, including for E itself. | `domain-user-identity/BindingId.java`; `infra-persistence-jpa/.../identity/ExternalIdentityJdbcRepository.java` (`ACQUIRE`, `DETACH`, exact predicates); migrations `V18__user_identity_binding_authority.sql`, `V20__external_identity_binding_lifecycle.sql` (fact uniqueness is E+R, not B); `JpaExternalIdentityBindingAdapter.acquire`; `JpaUserIdentityAuthorityAdapterPostgresTest.staleDetachCannotDeleteAReattachedOccurrence` uses distinct B values. | **MIGRATION** | Old Commands under B1 can become valid again if B1 is reused after detach: the occurrence fence loses its intended meaning. Remediation required: **YES**. Requires durable non-reuse of historical BindingIds and corresponding writer alignment. |
| A2 | B is never a revision/ordinal; no runtime ordering of B; “binding token” is not a third business identity. | B and R have separate types; freshness is compared on R, and Command matches B by equality. No separate binding-token model or B ordering was found in the scoped production Java/SQL search. | `BindingId.java`, `BindingRevision.java`; `JdbcCurrentBindingAdapter.apply`; `ExternalIdentityJdbcRepository.SELECT_EXACT_USER_ID`; static `rg` search for `bindingToken`, `binding_token`, B ordering/comparison in `app/`. | **NONE** | No separate gap. Remediation required: **NO**. |
| B1 | R belongs to E; successful attach/detach/reattach advances it monotonically and continuously, independently of B. | V20 stream PK `(issuer,subject)` holds `current_revision`. Writers create/lock that row, perform the exact authority mutation, advance `current+1`, then insert the fact in the same mandatory transaction. Conflict/stale detach returns before advance. Rollback undoes authority, stream and fact; persisted stream survives restart. Historical V18 associations start at revision 0 rather than an invented event. | `V20__external_identity_binding_lifecycle.sql`; `ExternalIdentityBindingStreamJdbcRepository` (`CREATE_IF_ABSENT`, `LOCK ... FOR UPDATE`, `ADVANCE` expected-revision update); `JpaExternalIdentityBindingAdapter.acquire/detach`; `JpaUserIdentityAuthorityAdapterPostgresTest` tests `staleDetachCannotDeleteAReattachedOccurrence`, `concurrentDetachHasExactlyOneWinnerAndDoesNotCreateARevisionGap`, `concurrentAttachAndDetachSerializeOnTheStreamWithoutLosingFacts`, `factAppendFailureRollsBackAuthorityAndRevisionTogether`, `revisionAllocationFailureAfterAuthorityMutationRollsBackWithoutAFact`. | **NONE** | Successful post-V20 mutations form R1, R2, R3 without a gap; failed attempts do not consume R. Remediation required: **NO**. |
| C1 | WRITE authority retains detached state and last R, allowing next attach at R+1 without consulting READ. | The authority is composite: `external_identity_binding_streams` retains E+R, while `external_identities` contains only active E→U+B. Detach deletes the latter but leaves the stream; next acquire locks the stream and uses its R. There is no explicit `state` column, but stream present plus active row absent represents DETACHED after a successful detach. | `V18__user_identity_binding_authority.sql`; `V20__external_identity_binding_lifecycle.sql`; `ExternalIdentityJdbcRepository.DETACH`; `ExternalIdentityBindingStreamJdbcRepository`; `JpaExternalIdentityBindingAdapter`; `JpaUserIdentityAuthorityAdapterPostgresTest.staleDetachCannotDeleteAReattachedOccurrence` checks R3 and three facts. | **NONE** | Detach does not erase the authoritative revision; R1 attached → R2 detached → R3 attached works for distinct B. Remediation required: **NO**. |
| D1 | Binding facts are durable append-only, one ordered revision per E; detach records the invalidated E,U,B,R, and the full history can reconstruct current state. | Successful writers insert facts; production Java has no fact UPDATE/DELETE, and `(issuer,subject,binding_revision)` is unique. **But** `ExternalIdentityDetached` and its row intentionally omit U (`user_id=NULL`), while the final canon explicitly includes U. Also V20 backfills stream R0 for V18 bindings **without** an Attached fact: their initial current state cannot be reconstructed exclusively from facts. The READ bootstrap instead reads primary `external_identities` at R0 and creates a synthetic **projection row**, not a synthetic fact. | `ExternalIdentityDetached.java`; `ExternalIdentityBindingFactRecordMapper.toRow`; `ExternalIdentityBindingFactJdbcRepository.APPEND`; `V20__external_identity_binding_lifecycle.sql` (shape constraint, R0 backfill); `JdbcBindingFactDiscoveryAdapter.findRevisionZeroPage`; `HistoricalBindingBootstrap.runPage`; `ExternalIdentityBindingLifecycleMigrationPostgresTest.v20BootstrapsOneLocalRevisionZeroStreamPerHistoricalIdentityWithoutFacts`; `Wa67BindingArchitectureTest.bindingFactsAreAppendOnlyAndBindingMutationsLockStreamBeforeAuthority`. | **MIGRATION** | The stored fact contract lacks U on detach and pre-V20 bindings have no originating fact. Append-only writes and R uniqueness hold for new mutations, but fact-only reconstruction is not complete for historical R0. Remediation required: **YES**. Requires alignment of durable fact shape and historical reconstruction semantics. |
| D2 | Stale detach/fact cannot replace a newer occurrence; retry, restart and multiple workers converge. | `detach` conditions on E+B under the per-E stream lock. Discovery rescans durable facts with an ephemeral keyset cursor; projection upsert accepts only higher R, treats lower R as stale, equal identical as duplicate, and equal divergent as error. Consumption slot claim/finalization protects concurrent workers. This works for distinct B; the B-reuse gap in A1 remains a separate higher-severity precondition failure. | `JpaExternalIdentityBindingAdapter.detach`; `ExternalIdentityJdbcRepository.DETACH`; `JdbcBindingFactDiscoveryAdapter.findNextEligible`; `BindingFactConsumptionLocator.execute`; `JdbcCurrentBindingAdapter.apply`; `BindingRuntimePostgresTest` tests `lateCommitBeforeTheEphemeralCursorIsFoundOnTheNextScan`, `technicalFailureRetriesAfterAReconstructedScanAndThenFinalizesIdempotently`, `lostClaimRollsBackProjectionBeforeWinnerAndMultipleWorkersConverge`. | **NONE** | Projection ordering/retry mechanics are proved for the modeled stream. Remediation required: **NO** (subject to A1/D1). |
| E1 | `CURRENT_BINDING(E)` is one mutable READ row with last R; ATTACHED has U+B, DETACHED has U=null **and B=null**. | READ V9 PK `(issuer,subject)` and revision upsert provide one row and preserve DETACHED@R after detach. However `binding_id UUID NOT NULL`, `CurrentBinding` requires non-null B, and the fact consumer writes the invalidated B into the DETACHED row. This directly contradicts the canonical DETACHED shape. | READ migration `V9__current_external_identity_binding.sql` (`binding_id uuid not null`, `ck_current_binding_shape` checks only U); `CurrentBinding.java` constructor; `BindingFactConsumptionLocator.execute`; `JdbcCurrentBindingAdapter.apply/map`; `CurrentBindingPersistencePostgresTest.appliesFirstUpdateTombstoneDuplicateStaleJumpAndOutOfOrderByRevisionOnly` explicitly expects non-null B on DETACHED. | **MIGRATION** | A detached READ row retains and can carry the old occurrence B, contrary to the specified null representation. Remediation required: **YES**. Requires READ schema/data and projection-shape alignment. |
| E2 | Absence of projection and known DETACHED are distinguishable internally; READ converges after late/retried facts and WRITE never consumes it. | `find(E)` returns empty only when no row exists; DETACHED is a retained row. Self-service `getAttached` filters DETACHED to empty and HTTP returns 404 for both; the canon leaves detached HTTP status open. Late facts, retry/restart and multi-worker tests cover projection convergence. WRITE Command resolves through primary `ExternalIdentityBindingPort`, not READ. | `JdbcCurrentBindingAdapter.find/apply`; `GetCurrentBindingService.getAttached`; `CurrentBindingController.get`; `BindingRuntimePostgresTest` seven tests; `ExecuteRecordedCommandService.prepareAuthorization`; `Wa67BindingArchitectureTest.directProductionSqlAccessesStayInsideTheirDeclaredOwners`. | **NONE** | Internal known-detached distinction exists; HTTP intentionally does not expose it. Remediation required: **NO** (E1 still applies). |
| F1 | V2 durable Command contains E+B+payload+AuthN evidence, no R, no admission-resolved U, no precalculated business permissions or raw JWT; V1 is legacy. | `TargetCommandEnvelope` has E, B and evidence (external authorities, valid-until); `RecordedCommand` adds id/type/payload/time. V19's version-2 shape requires subject/B/authorities and nulls V1 user/permissions/timestamps. There is no R column/model/API. POST produces V2; V1 remains readable/consumable for historical rows. | `TargetCommandEnvelope.java`, `CommandAuthenticationEvidence.java`, `RecordedCommand.java`; `SubmitRecordedCommandService.submit`; `CommandAuthenticationEvidenceFactory.create`; `JpaRecordedCommandAdapter.insert`; migration `V19__recorded_command_envelope_expand.sql`; `JpaRecordedCommandAdapterPostgresTest.insertsAndReloadsTheExactTargetV2EnvelopeWithoutLegacyIdentityOrJwt`, `reloadsHistoricalV1WithoutInventingSubjectBindingOrTargetEvidence`; `CommandAdmissionPostgresTest.knownIdentityAndCurrentBindingPersistExactTargetEnvelopeWithoutPrimarySelect`. | **NONE** | V2 is correctly separated from V1 and from R. Remediation required: **NO**. |
| G1 | V2 HTTP admits authenticated E with syntactically valid B, without User/Pot/READ/business reads or AuthZ, and returns 202 after durable capture. | Controller takes E only from `AuthenticatedExternalPrincipal`, checks request shape/UUID B, serializes opaque payload and calls the insert-only transaction. Unknown, false and detached B are accepted. SQL-capture test excludes SELECT on User/Identity, Pot, Event, Consumption and READ during POST. | `AsyncCommandController.submit`; `SubmitRecordedCommandInput.java`; `SubmitRecordedCommandService.submit`; `CommandAdmissionPostgresTest` tests `knownIdentityAndCurrentBindingPersistExactTargetEnvelopeWithoutPrimarySelect`, `unknownFalseAndDetachedBindingsAreIndistinguishablyAcceptedAsTargetV2`, `missingEmptyAndMalformedBindingAreStructuralRejectionsWithoutDurableRow`; `WriteSideClosurePostgresTest.committedHttpAdmissionIsConsumedThroughTheRealPollingLoop`. | **NONE** | Admission is structurally open and durable. Remediation required: **NO**. |
| H1 | Worker reloads V2, resolves exact E+B→U, evaluates capabilities and current business state, mutates/appends/outcomes, with B fenced until business commit. | `TransactionalExecuteConsumptionUseCase` wraps execution, provenance and terminal claim CAS in one transaction. The Command reloads, then `lockCurrentBinding(E,B)` uses exact `SELECT ... FOR UPDATE` on the active row. Pot dispatch/append and outcome publication occur before terminal CAS/commit. Attach/detach writers lock the per-E stream then insert/delete the same active row; a detach of the locked B waits. Rollback releases the lock and undoes effects; lost claim fails terminal CAS. The primitive is this implementation's choice, not a canon requirement. | `TransactionalExecuteConsumptionUseCase.execute`; `ExecuteConsumptionService.execute`; `CommandConsumptionExecution.execute`; `ExecuteRecordedCommandService.execute/prepareAuthorization`; `ExternalIdentityJdbcRepository.LOCK_EXACT_USER_ID`, `ACQUIRE`, `DETACH`; `JpaExternalIdentityBindingAdapter.acquire/detach`; `CommandConsumptionPostgresTest.targetBindingLockIsHeldUntilBusinessCommitAndBlocksConcurrentDetach`, `targetDetachedBeforeLockIsRejectedWithoutBusinessMutation`, `targetTechnicalRollbackReleasesBindingLockAndKeepsNoMutation`, `takeoverRollsBackTheLosingCommandAndOnlyTheWinnerCommits`. | **NONE** | No observed TOCTOU window with distinct, never-reused B. A1 means this otherwise sound exact-B fence can be defeated by historical B reuse. Remediation required: **NO** for the transaction primitive; **YES** under A1. |
| I1 | Command fence is E+B; R orders lifecycle/projection and is never an execution precondition. | V2 Command and worker have no R; worker checks exact E+B and does not compare revisions. | `TargetCommandEnvelope.java`; `V19__recorded_command_envelope_expand.sql`; `ExecuteRecordedCommandService.prepareAuthorization`; negative scoped `rg` search for `BindingRevision`/`binding_revision` in Command/admission/consumer packages. | **NONE** | Command fence = `(E,B)`; R = lifecycle/projection order. Remediation required: **NO**. |
| J1 | Unknown E, no active binding, false/old B, detach, same/other-U reattach all yield public `CALLER_IDENTITY_NOT_CURRENT` without secondary revealing lookup. | Empty exact lock lookup maps to one rejection and `CommandOutcome.Rejected` publishes that code; there is no fallback E→U lookup. Tests exercise unknown, wrong, detached and both reattach targets. The result reader exposes only the public code to the matching E. The B-reuse scenario A1 can instead execute an old Command and is an exception to the intended stale-B outcome. | `ExecuteRecordedCommandService.prepareAuthorization/execute`; `CommandConsumptionExecution.execute`; `CommandConsumptionPostgresTest` tests `targetUnknownExternalIdentityIsRejectedWithTheSamePublicReason`, `targetWrongBindingIsRejectedWithTheSamePublicReason`, `targetDetachedBeforeLockIsRejectedWithoutBusinessMutation`, `targetStaleBindingIsRejectedAfterSameUserReattach`, `targetStaleBindingIsRejectedAfterOtherUserReattach`; `GetCommandResultService.visibleResult`. | **MIGRATION** | Public rejection is non-oracle on tested paths, but cannot be guaranteed for an old B that becomes current again. Remediation required: **YES**. Requires the A1 durable non-reuse guarantee. |
| K1 | actor == subject; B is not delegation/impersonation/supervision. | V2 captures the authenticated principal's exact E; no distinct effective subject or delegation field/meaning was found in the binding/Command models or ports. | `AuthenticatedExternalPrincipal` use in `AsyncCommandController.submit`; `SubmitRecordedCommandService.submit`; `TargetCommandEnvelope.java`; `BindingId.java`; scoped static search for binding token/delegation/impersonation symbols. | **NONE** | No actual actor/subject violation found. Remediation required: **NO**. |
| L1 | READ controllers use READ only; `COMMAND_RESULT` is served from READ; self-service binding does not read primary; AuthN is separate. | `CommandResultController` calls `GetCommandResultService`, which loads the exact READ projection. V2 visibility compares the stored E; V1 legacy visibility uses `CurrentBindingProjectionPort`, wired to READ, not primary. Self-service controller reads `CurrentBindingProjectionPort`. The READ consumer/bootstrap may read primary as its explicit projection contract, outside synchronous HTTP READ; Command WRITE never consumes the projection. | `CommandResultController.get`; `GetCommandResultService.get/visibleResult`; `CommandResultReadConfiguration.legacyCurrentBindingUserQuery`; `CurrentBindingController.get`; `JdbcBindingFactDiscoveryAdapter.findRevisionZeroPage`; `ExecuteRecordedCommandService.prepareAuthorization`; `CommandCompletionE2EPostgresTest.httpCreateAndUpdateTraverseWorkersAndReadAuthorizedExactPotVersions`. | **NONE** | READ/WRITE request boundary is respected on delivered paths. Remediation required: **NO**. |

### Exact lifecycle counterexample behind A1/J1

With E attached under B1, `detach(E,B1)` removes the only row constrained by V18's unique
`binding_id`. A later `acquire(E,U,B1)` can insert B1 again: V20 checks uniqueness of E+R, not B.
The resulting facts can be `ATTACHED(E,U,B1,R1)`, `DETACHED(E,B1,R2)`,
`ATTACHED(E,U,B1,R3)`. A previously recorded V2 Command under `(E,B1)` passes the worker's exact
lookup at R3, although its original occurrence ended at R2. This is a schema/port-derived
counterexample, **not** an observed production incident or an executed test case. Using a new B
avoids it, but the final canon requires the invariant to hold for every successful attach.

### Authority, facts and projection are distinct

After detach, `external_identities` has no active E row; `external_identity_binding_streams`
retains E and the last R. This suffices for the next authoritative R+1 without a READ lookup. The
fact table keeps successful mutation rows, including the invalidated B, but its DETACHED row has
`user_id=NULL`. The READ table keeps a mutable DETACHED row and last R, but also keeps that B even
though the canonical DETACHED projection requires both U and B null. For V18 historical bindings,
the READ `ATTACHED@0` row is synthesized by bootstrap from primary authority; **no historical
Attached fact is synthesized**, so fact-only replay cannot recreate that initial association.

## Six-lot assessment

| Lot | What remains valid | Incomplete under final canon / classification | Dependency between remediations |
|---|---|---|---|
| WA.1 | Single neutral E, U and opaque UUID B model; provider-neutral principal. | **NONE** for delivered scope. The value object itself neither allocates nor durably reserves B; the later persistence gap belongs to WA.2/WA.6. | None intrinsic. |
| WA.2 | Active `(E,B)→U` exact lookup, active B uniqueness, FK, and row-lock primitive remain valid. | **MIGRATION**: `external_identities` deletes detached B, so its unique constraint cannot prevent historical B reuse. The plan's statement that binding writers generate a fresh B is not enforced by the `acquire(E,U,B)` port (secondary CODE/DOC dimension). | Durable non-reuse must be established before claiming the Command fence invariant. |
| WA.3 | V19 V2/V1 disjoint envelopes, no R/U/permissions in V2, historical V1 reload remain valid. | **NONE**. Legacy V1 retains its deliberately historical snapshot behavior; it is not the target V2 contract. | None intrinsic. |
| WA.4 | Exact `(E,B)` row lock, one transaction through business commit/outcome/claim CAS, rollback and tested non-oracle paths remain valid. | **MIGRATION** as a dependent gap: B reuse (WA.2/WA.6) can resurrect an old Command without any flaw in the row-lock duration. The plan's WA.4 DONE claim is therefore conditional on a guarantee the store lacks (secondary DOC). | Depends on the A1 historical B guarantee; no different lock primitive is inferred. |
| WA.5 | Authenticated E capture, syntax-only B validation, no primary/READ decision read, durable V2 then 202 remain valid. | **NONE**. | None intrinsic. |
| WA.6 | Per-E R stream, transactional increments, append-only production inserts, fact discovery/retry, retained DETACHED READ row and READ-only endpoints remain valid. | **MIGRATION**: detach fact omits U; historical R0 associations have no source fact; READ DETACHED has non-null B. The plan describes the old DETACHED shapes and calls fact-only reconstruction complete (secondary DOC). The tests explicitly encode non-null B and do not cover B reuse (secondary TEST). | Fact contract/history and READ detached shape must align before fact-only rebuilding and full `CURRENT_BINDING` conformity can be claimed. |

## Answers to the 16 critical questions

| # | Factual answer |
|---|---|
| 1 | **No** B-as-version/ordinal or B ordering found in runtime; only equality is used. |
| 2 | **Yes** R is stored per E in `external_identity_binding_streams(issuer,subject,current_revision)`. |
| 3 | **Yes for successful post-V20 mutations**: R advances by one under a per-E lock in one transaction; failures/rollbacks do not consume it. Historical V18 bindings begin at R0 without an event. |
| 4 | **Yes** the stream row persists after DETACH and holds the last R. |
| 5 | **Yes** next successful ATTACH uses R+1; its B is not guaranteed fresh by the store. |
| 6 | **Yes in production DML**: facts are inserted, not business-updated/deleted; one R per E is constrained. The canonical fact content/history is incomplete (D1). |
| 7 | **Yes**, READ retains a DETACHED row and last R; **no**, its B is not null as required. |
| 8 | **Yes internally**: no row differs from a DETACHED row. Self-service HTTP returns 404 for both, an allowed status choice in the canon. |
| 9 | **Yes** for V2, plus ordinary Command metadata (`commandId`, `commandType`, `submittedAt`). V1 remains legacy. |
| 10 | **No** Command V2 contains no R, in Java or SQL. |
| 11 | **Yes** the runtime lookup is exact `(E,B)`; historical B reuse can make a stale occurrence appear current. |
| 12 | **Yes**, the active-row lock is held through the transaction's business commit. |
| 13 | **No** observed detach/reattach TOCTOU for distinct B: detach waits on the row lock and the relevant PostgreSQL test passes. B reuse is a separate occurrence-identity failure. |
| 14 | **No** `CURRENT_BINDING` is not consumed by WRITE Command execution. |
| 15 | **Yes on modeled mismatch paths**, all yield one public code; **not guaranteed** if an old B is reused and ceases to mismatch. |
| 16 | **No** B is not used for delegation or impersonation. |

## Final verdict and Git discipline

```text
BINDING CANON ALIGNMENT AUDIT: PASS WITH GAPS
Canonical model reopened: NO

WA.1: NONE
WA.2: MIGRATION
WA.3: NONE
WA.4: MIGRATION
WA.5: NONE
WA.6: MIGRATION

Runtime/persistence remediation required: YES
Documentation-only remediation required: YES
Test-proof remediation required: YES
Blocking unknowns: 0

Branch: v2-make-it-pull
Start HEAD: ecdb603d77a84bef7886086c6a0e8ed5d0a90310
Final HEAD: ecdb603d77a84bef7886086c6a0e8ed5d0a90310
Files changed: docs/steps/current/WRITE_ADMISSION/Binding_Canon_Alignment_Audit.md (new, only file)
Working tree: only the new audit file, untracked; no pre-existing changes
git diff --check: PASS; separate --no-index whitespace check of the untracked file: PASS
```

`Documentation-only remediation required: YES` means the historical `Step_Plan.md` descriptions
need eventual alignment after human review; it does **not** downgrade the persistence findings.
`Test-proof remediation required: YES` concerns regression proof for non-reuse and canonical
DETACHED/fact shapes after those gaps are corrected. No remediation was performed in this audit.
