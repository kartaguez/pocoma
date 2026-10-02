# WA.7 Readiness Audit — Command V1 contraction

## 1. Repository baseline

Branch `v2-make-it-pull`, start HEAD `6eb277b19ad84460d21775b0f236184bc56125c9`; `HEAD...origin/v2-make-it-pull = 0/0`. WA.6 is CLOSED, WA.7 NOT STARTED according to `Step_Plan.md`. Two pre-existing untracked files (`AGENTS.md`, `docs/testing/Reactor_Verification_Policy.md`) are outside this scope. Authority is `Step_Canon.md` WA1–WA11, with `Step_Plan.md` sequencing. This audit examines source and migrations; it does not measure any deployed database.

## 2. Executive verdict

**READY WITH CONDITIONS — WA.7 then WA.8 can run in a single gated wave only if the actual stores have zero V1 Commands and zero V1 result artifacts, all old writers and workers are stopped for cutover, and the full preflight succeeds.** The repository alone cannot prove these runtime conditions. There is no unresolved architecture decision for an empty V1 population. A terminal V1 Command is *not* drained for contraction: its row and result can still be required. A populated installation cannot reach the required gate by processing pending work alone. Do not delete, rewrite, relabel, or synthesize E/B for historical V1. Such an installation needs a separate retention/visibility decision before complete contraction.

## 3. Inventory of remaining V1 surface

Disposition applies only after the data gates pass. Keep historical migrations immutable.

| Surface | Evidence | WA.7 disposition |
|---|---|---|
| `AuthorizationSnapshot` | `engine-command/model`; frozen U, permissions, issuer/times; V1 envelope and execution authorization | Remove; retain `ResolvedCommandAuthorization` and worker capability translation. |
| Envelope model | `RecordedCommand` V1 constructor/`authorization()`; sealed `RecordedCommandEnvelope`; `RecordedCommandEnvelopeVersion` V1/V2; `TargetCommandEnvelope` | Remove V1 overload/view and V1 discriminator. Simplify to target envelope; V2 type may remain as named model. |
| V1 SQL | WRITE V8/V19: `auth_user_id`, `auth_authenticated_at`, `auth_issued_at`, `auth_permissions_json`, `envelope_version DEFAULT 1`; version/shape/permission CHECKs | Drop four V1 columns and physical version/default once V1 count is zero. Replace transitional CHECKs with target-only constraints. |
| V2 SQL | `auth_issuer`, `auth_subject`, `binding_id`, `auth_valid_until`, `auth_external_authorities_json`, ID/type/payload/time | Retain; make subject, B and external authorities NOT NULL. Preserve discovery index. |
| Store/mappers | `RecordedCommandRow`, `JpaRecordedCommandRepository`, `RecordedCommandRecordMapper`, `JpaRecordedCommandAdapter` | Remove V1 fields, inserts, selects, permission serialization and switch; target-only round trip. Preserve `RecordedCommandPort`. |
| Command execution | `ExecuteRecordedCommandService`: V1 bypasses binding observe/fence and uses frozen permissions; V2 observes `(E,B)`, translates capabilities and CAS-fences | Remove V1 arms; preserve V2 expiry, authority resolution, policies, mutation/outcome, optimistic fence through commit and recovery. |
| HTTP admission | `SubmitRecordedCommandService`, request/controller | Keep WA.5 E+B producer and syntactic B check. Primary resolver was already removed. |
| Discovery/Consumption | `CommandConsumptionKeys` (`COMMAND`, `COMMAND_PROCESSOR`), locator, discovery, slots, claims, inputs/results, failure policy, runtime worker | Keep generic mechanisms; audit their V1-linked durable records. No V1-specific key is required. |
| Outcome/provenance | `command_outcomes` FK to Command, `command_terminal_events` FK to outcome; Consumption attempt/result provenance | Keep generic structures; their V1-linked rows survive terminal processing and prove that terminal != deletable. |
| Projection loader | `JdbcCommandResultProjectionInputLoader` | Remove `envelope_version`/`auth_user_id` branch; load exact E from target row in consistent transaction. |
| READ projection | `CommandResultVisibility.LegacyUser`, V1 `submittedByUserId` arm in `CommandResultProjector` and `CommandResultProjectionDefinition` | Remove only after READ zero-V1 gate. Retain exact-E payload and physical `COMMAND_RESULT` key/type/version. |
| GET result | `GetCommandResultService` V1 current-U branch; `LegacyCurrentBindingUserQuery`; `CommandResultReadConfiguration` legacy bean | Remove V1 logic and bean. Retain exact-E visibility, non-oracle `NotFound`, AuthN and separate self-service current-binding service. |
| Wiring/config | `runtime-task-consumption-worker` loader/projector wiring, `runtime-command-consumption-worker`, `pocoma.command-result-read.enabled` | Keep generic wiring/flag, simplify dependencies to target-only. |
| Tests/fixtures | `CommandModelTest`, `CommandDispatcherTest`, `ExecuteRecordedCommandServiceTest`, `PotCommandUseCaseAdapterTest`, persistence/worker Postgres suites, `CommandResultTest`, projection-chain/completion E2E, JSON schema, web admission | Replace dual-format fixtures with target-only positives and a V1 migration-rejection fixture; keep outcome, concurrency, restart and READ cases. |
| Architecture | `HexagonalArchitectureTest` V1 allowances and `Wa67BindingArchitectureTest` | Tighten current-production scans; retain CAS, READ/WRITE, ownership and binding guards. |
| Active docs | `Step_Plan.md`, `Step_debt.md`, `step_audit.md`, architecture intake/persistence/runtime docs | Mark debt discharged only after gate, document cutover; preserve canon and historic audit text. Other repository uses of “legacy” (Event/generic Consumption) are separate and not WA.7 targets. |

## 4. Durable-data / backlog analysis

V19 assigns `envelope_version=1` to all prior V8 Commands, leaving their E subject and B unknown. `recorded_commands.command_id` is the durable root. `command_outcomes.command_id` references it, `command_terminal_events.command_id` references outcome. `consumption_slots` stores `COMMAND` plus Command UUID text in `consumable_components[0]`; claims, inputs and results reference its `slot_id`. A `DONE` slot still retains attempts and provenance. The task worker derives a result from outcome plus recorded envelope. READ V7 stores `COMMAND_RESULT` in `pocoma_read.projection_root`/`projection_artifact`; V1 payload carries `submittedByUserId`, while V2 carries exact issuer+subject. A V1 result can remain readable after the Command finishes under V1's READ current-binding/U rule.

Therefore **“legacy V1 backlog drained” for full WA.7 = zero V1 rows in recorded_commands AND zero V1/mixed/unknown COMMAND_RESULT artifacts, under a cutover with no in-flight V1 writer or result worker**. Zero *nonterminal* V1 is insufficient. Zero V1 rows is necessary to drop their exclusive fields without erasing data or inventing E/B. Zero V1 artifacts is necessary to remove the V1 GET policy without breaking historic result access. Generic V2 outcome, terminal-event, Consumption and projection records remain. No arbitrary deletion or transformation of V1 history is permitted.

## 5. Exact WA.7 preflight gates

Run against the actual WRITE and READ stores after quiescing old binaries and draining transactions. Every count except the version histogram below must be zero. Materialize the checks as `DO` blocks that raise an exception on positive counts; a report alone is not a gate. The WRITE gate and destructive DDL must share one PostgreSQL transaction and lock `recorded_commands` against concurrent inserts/updates. READ has a separate database and must be gated under stopped projection workers. Re-run immediately before cutover.

```sql
-- WRITE diagnostics and fail conditions
SELECT envelope_version, count(*) FROM recorded_commands GROUP BY envelope_version;
SELECT count(*) AS bad_version FROM recorded_commands
 WHERE envelope_version IS NULL OR envelope_version NOT IN (1,2);
SELECT count(*) AS v1_total FROM recorded_commands WHERE envelope_version = 1;
SELECT count(*) AS v1_nonterminal FROM recorded_commands c
 LEFT JOIN command_outcomes o USING (command_id)
 WHERE c.envelope_version=1 AND o.command_id IS NULL;
SELECT count(*) AS v1_no_terminal_event FROM recorded_commands c
 LEFT JOIN command_terminal_events e USING (command_id)
 WHERE c.envelope_version=1 AND e.command_id IS NULL;
SELECT count(*) AS v1_slots FROM consumption_slots s
 JOIN recorded_commands c ON s.consumable_type='COMMAND'
  AND jsonb_typeof(s.consumable_components)='array'
  AND jsonb_array_length(s.consumable_components)=1
  AND s.consumable_components->>0=c.command_id::text
 WHERE c.envelope_version=1;
SELECT count(*) AS v1_pending_slots FROM consumption_slots s
 JOIN recorded_commands c ON s.consumable_type='COMMAND'
  AND jsonb_typeof(s.consumable_components)='array'
  AND jsonb_array_length(s.consumable_components)=1
  AND s.consumable_components->>0=c.command_id::text
 WHERE c.envelope_version=1 AND s.status <> 'DONE';
SELECT count(*) AS malformed_command_slots FROM consumption_slots s
 WHERE s.consumable_type='COMMAND' AND NOT CASE
  WHEN jsonb_typeof(s.consumable_components)='array'
   AND jsonb_array_length(s.consumable_components)=1
  THEN (s.consumable_components->>0) ~
   '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
  ELSE false END;
SELECT count(*) AS v2_invalid_shape FROM recorded_commands
 WHERE envelope_version=2 AND
 (auth_issuer IS NULL OR btrim(auth_issuer)='' OR auth_subject IS NULL
  OR btrim(auth_subject)='' OR binding_id IS NULL OR auth_valid_until IS NULL
  OR auth_external_authorities_json IS NULL
  OR jsonb_typeof(auth_external_authorities_json)<>'array'
  OR auth_user_id IS NOT NULL OR auth_authenticated_at IS NOT NULL
  OR auth_issued_at IS NOT NULL OR auth_permissions_json IS NOT NULL);
```

The V1 slot counts diagnose links; `v1_total=0` dominates them. Inspect `consumption_claims`, `consumption_inputs`, `consumption_results` for any V1 slot through `slot_id`; never erase them to force a zero count. An orphan Command slot is an integrity finding, not permission to contract. Verify its UUID against `recorded_commands`. The final migration should raise on `v1_total`, bad versions, malformed slots and invalid target shape. Transactional sample:

```sql
LOCK TABLE recorded_commands IN SHARE ROW EXCLUSIVE MODE;
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM recorded_commands WHERE envelope_version IS DISTINCT FROM 2)
 THEN RAISE EXCEPTION 'WA.7: non-V2 recorded Command remains'; END IF;
 IF EXISTS (SELECT 1 FROM recorded_commands WHERE auth_subject IS NULL OR btrim(auth_subject)=''
     OR binding_id IS NULL OR auth_external_authorities_json IS NULL
     OR jsonb_typeof(auth_external_authorities_json)<>'array')
 THEN RAISE EXCEPTION 'WA.7: invalid target Command'; END IF;
END $$;
```

```sql
-- READ: V1 or incoherent/mixed result payloads block V1 parser removal.
SELECT count(*) AS legacy_or_invalid_result FROM pocoma_read.projection_root r
 JOIN pocoma_read.projection_artifact a ON a.projection_root_id=r.id
 WHERE r.projection_type='COMMAND_RESULT' AND r.target_object_type='COMMAND'
 AND (jsonb_typeof(a.payload)<>'object'
  OR a.payload ? 'submittedByUserId'
  OR a.payload->>'visibility' IS DISTINCT FROM 'EXACT_EXTERNAL_IDENTITY'
  OR jsonb_typeof(a.payload->'visibleToExternalIdentity')<>'object'
  OR NULLIF(a.payload#>>'{visibleToExternalIdentity,issuer}','') IS NULL
  OR NULLIF(a.payload#>>'{visibleToExternalIdentity,subject}','') IS NULL);
SELECT count(*) AS command_result_failures FROM pocoma_read.projection_failure
 WHERE projection_type='COMMAND_RESULT' AND target_object_type='COMMAND';
```

`legacy_or_invalid_result` must be zero. Diagnose projection failures and pending task Consumption before WA.8; V2 failures need repair/retry but do not themselves prove V1. A READ V1 artifact without a WRITE V1 root still blocks contraction. Do not drop its payload.

## 6. Target schema and code after WA.7

Final `recorded_commands`: `command_id uuid PK`, `command_type text NOT NULL`, `payload_json text NOT NULL`, `submitted_at timestamptz NOT NULL`, `auth_issuer text NOT NULL`, `auth_subject text NOT NULL`, `binding_id uuid NOT NULL`, `auth_valid_until timestamptz NOT NULL`, `auth_external_authorities_json jsonb NOT NULL`. Keep nonblank command type/issuer constraints and `(submitted_at,command_id)` index. Add nonblank subject and authorities-is-array CHECKs. Drop `auth_user_id`, `auth_authenticated_at`, `auth_issued_at`, `auth_permissions_json`, `envelope_version`; drop `ck_recorded_commands_auth_permissions`, `ck_recorded_commands_envelope_version`, `ck_recorded_commands_envelope_shape`. The version default `1` must disappear. `auth_issuer` and `auth_valid_until` already are NOT NULL; set NOT NULL on subject/B/authorities. Do not add U or BindingRevision to target envelope.

The physical V1/V2 discriminator becomes unnecessary after a one-shape contraction. Keep `EXACT_EXTERNAL_IDENTITY` in READ JSON as an explicit visibility tag; it is not a V1 branch. Keep generic Command, outcome, Consumption and projection ports. Fresh install must apply original V8/V19 then new forward-only migration, yielding same final schema as upgrade. Never edit applied V8/V19. Upgrade containing any V1 row aborts before DDL with rows intact.

## 7. COMMAND_RESULT analysis

Today the loader selects `envelope_version`: V1 takes `auth_user_id`, V2 exact `auth_issuer/auth_subject`. Projector/schema support disjoint V1 `submittedByUserId` and V2 `visibility=EXACT_EXTERNAL_IDENTITY` plus `visibleToExternalIdentity`. GET reads only READ projection, then V1 resolves authenticated E to current attached U through `CURRENT_BINDING` READ and compares U; V2 compares exact issuer+subject from payload with authenticated E. V1 visibility is eventually revoked after detach; reattach to same U can restore it. V2 visibility is independent of binding lifecycle and cannot transfer to another E. WA.7 removes V1 branches only when no V1 artifact exists.

Keep physical `COMMAND_RESULT` type/key/target version 1, outcome fields and `APPLIED/REJECTED/FAILED` parsing. Keep exact-E ownership, `NotFound` for missing/pending/nonowned, invariant failure for malformed payload, and no primary read in GET. The projection worker may read WRITE to materialize after commit; that is separate from synchronous HTTP READ. Self-service binding retains its READ projection and is not deleted with V1 result lookup.

## 8. Migration / deployment / rollback analysis

**PREFLIGHT → CONTRACT → VERIFY.** First verify V2-only admission and WA.6 worker cutover on every instance, stop all API/Command/Projection binaries that could write old shape, end in-flight transactions, run WRITE/READ gates. In a transactional WRITE migration after V23: acquire lock, repeat fail-closed gates, remove version and obsolete CHECKs/default, drop V1 fields, set target NOT NULL and CHECKs. Any bad data or lock failure aborts migration atomically. READ needs a separate zero-V1 gate before deploying V2-only GET/projector; no READ schema deletion is required. Verify final `information_schema` column/nullability set and `pg_constraint` list, zero invalid rows, equal fresh/upgrade schemas and target round trip. Start only WA.7 binaries.

WA.6 binary is incompatible after contraction: its repository SELECT/INSERT names dropped fields and its projection loader selects `envelope_version`. Mixed versions across DDL are forbidden. Before DDL commit, rollback to WA.6 is possible on unchanged schema; failed Flyway transaction leaves data intact. After commit, application-only rollback to WA.6 is impossible. Restore a consistent pre-contract WRITE+READ backup under stopped writers, accounting for intervening writes, or roll forward with WA.7. No reverse migration may invent E/B. DDL commit is the point of no return.

## 9. Exact implementation plan

1. Add preflight diagnostics and a fail-closed WRITE Flyway migration after V23. Test fresh install, V23 target-only upgrade, pending V1, terminal V1, invalid shape, and atomic failure.
2. Simplify Command model, row, repository, mapper, store and execution to target-only. Preserve worker `(E,B)` resolution, capability translation, optimistic fence through commit, failure recovery and outcomes.
3. Remove V1 result visibility, loader, projector/schema/GET arm and legacy current-binding query bean. Preserve exact-E visibility, all terminal outcomes, non-oracle HTTP mapping and self-service READ.
4. Replace V1 test fixtures with target fixtures; retain a negative historical V1 upgrade fixture. Tighten architecture scans. Update active debt/operational docs but not canon or applied migrations.
5. Run migration and runtime PostgreSQL tests, full `./mvnw test -q`, static scans and cutover dry run against representative backup. Gate WA.8 on all WA.7 results.

## 10. WA.7 acceptance matrix

| Proof | Required result |
|---|---|
| Actual WRITE/READ preflight | Zero V1/invalid counts and no in-flight old writer; any positive count aborts without mutation. |
| Fresh/upgrade migrations | Same target schema; V1 fixture blocks upgrade atomically; V8/V19 unchanged. |
| Target round trip | Exact E/B/authorities/expiry/payload/time; no U/snapshot/JWT/R. |
| Worker | Unknown/stale B non-oracle rejection; current B executes with fence; lost CAS rolls back mutation/outcome. |
| Result | Exact-E ownership for applied/rejected/failed; pending/absent/nonowner non-oracle; zero V1 artifacts before parser removal. |
| Architecture | No current production V1 symbols/columns or primary GET reads; historic SQL/docs/test fixtures exempt. |
| Deployment | No mixed WA.6/WA.7 binaries; backup and rollback rehearsal; full reactor green. |

## 11. WA.8 readiness

After WA.7 gate, WA.8 needs proofs rather than design: HTTP authenticated E + syntactically valid B returns `202` and persists target-only row; unknown E admitted, malformed B rejected with no row, zero admission primary SELECT. Prove worker authority resolution, capabilities and fence; stale B after detach/reattach same/other U, B2 success; Command/Detach CAS win/loss and rollback; retry, claim loss, restart, multi-worker idempotence; result projection and exact-E GET with pending/not-found/nonowned non-oracle; self-service attached/detached/absent from READ; zero primary GET reads and zero READ decisions in WRITE; migration/architecture scans show total V1 absence. Keep WA.8 behind WA.7 gate.

## 12. Blocking questions

Architectural blockers for a genuinely zero-V1 deployment: **0**. Runtime data population is unknown in this repository audit. If V1 rows or historic V1 result artifacts exist, complete WA.7 is **not ready for that installation**; preserving their data and access while eliminating V1 support requires a separately decided and proven archival/retention design. The canon and code cannot recover an exact E or B for V1. Do not downgrade this to a cleanup detail or bypass the gate.

**READY WITH CONDITIONS — WA.7 then WA.8 can be implemented in one gated wave only after zero V1 Commands and zero V1 READ artifacts are proven on the target stores and an incompatible-binary cutover is coordinated.**
