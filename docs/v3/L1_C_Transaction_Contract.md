# L1-C — Internal durable Pot execution contract

Authority: SPEC-01/02/03/05/10 and approved L1 plan §18. C-01/C-02 and all pure transitions from A are unchanged. This contract documents implementation; it adds no business rule. See [execution report](L1_C_Execution_Report.md) for measured results and limits.

## Entry and physical composition

`ExecutePotCommand.execute(requestId, claimToken)` is the bounded application handoff for **one acquired claim / one complete attempt**. It loads the admitted intention; callers supply neither a chosen User nor authorization booleans. It performs no acquisition, Discovery, polling, internal retry loop, Identity mutation or READ write. A later attempt requires normal reacquisition. `V3PotCommandComposition.assemble` explicitly constructs the real JDBC adapters and owner using one WRITE datasource and one JpaTransactionManager; it installs no runtime configuration or HTTP endpoint. No changes to the generic V2 Consumption lifecycle, its BindingId contract, or legacy public runtime activation are made.

The old Command decoder/dispatch/services and V2 publication protocol remain historical runtime consumers. They are not used by this V3 handoff. Converting the generic Worker/admission/READ paths is deferred to their lots; no adapter maps BindingId V2 into V3 authority. The executable handoff itself is in engine-write-pot; an additional forwarding service in engine-consume-command would add no behavior. This is the plan §18.5f/6 bounded handoff, not a claim of complete Engine V3 integration.

Preparation uses a caller-owned read-only REPEATABLE_READ snapshot. All facts, including the Identity witness, are observed on the same WRITE authority in the PostgreSQL proof. The pure domain decides from selective B facts. The private prepared value holds the actual typed proposal in an engine-owned write closure; it is never persisted, exposed as a commit receipt, or reused after a failed attempt. The fixed observedVersion is reloaded from the immutable request on every acquisition.

Finalization uses a new READ_COMMITTED physical transaction. It performs no unprotected decision reads after preparation; current locking reads validate all witnesses. READ_COMMITTED is deliberate: after waiting for a lock it sees the actual committed authority, including a previously absent Pot. B snapshot reads stay in preparation; its revision methods already support writable caller transactions.

| Order | Protection / operation | Authority and guarantee |
|---|---|---|
| 1 | Exclusive slot row lock, then a **separate fresh** clock_timestamp query | Token, AVAILABLE and deadline checked after waiting. No partition ownership lookup; lock retained to physical resolution, including when deadline passes later |
| 2 | Reload durable request under slot protection | Original EUI/token/intention/observation must equal preparation; TERMINAL replay is recovered without a second effect |
| 3 | Protect EUI occurrence via PotActorAuthority.protect | Full witness revalidated; implementations must also protect absence/invalid token. Authority lock retained through commit |
| 4 | Per-Pot transaction advisory lock, then identity row FOR UPDATE and cursor comparison | Advisory lock also protects negative existence/CreatePot. Cursor must equal prepared workingVersion for Changed, Unchanged **and Rejected** |
| 5 | Effective proposal only: B recordVersion/createPot and exact typed revisions | Version insertion performs conditional UPDATE cursor=V→V+1 in a trigger; one candidate wins. B writer_xid xid8 still requires candidate ownership by this same transaction |
| 6 | Terminal result/status and deletion of protected slot; stage minimal events | No business changes for no-op/rejection. One POT event for effective multi-component mutation, none otherwise; exactly one completion |
| 7 | Compare staged changedComponents with **all eight actual revision tables at candidate V**; check writer_xid; flush JPA; force B deferred constraints | No additional business lock after this prefix. Request/result/event correspondence also has deferred read-only physical-commit checks |
| 8 | Close owner to further writers; transactional POT counter then COMMAND_COMPLETION counter and publication positions | No nextval for event positions; locks/counter updates rollback together. Prefix business writes complete before sequence acquisition |
| 9 | Physical commit or full rollback | Returned Changed is not presumed durable until owner confirms resolution |

All mutations of the Pot, active Headers, attachments, weights and financials go through the same version authority in this handoff. Thus its protected global V covers the **entire** exhaustive usage predicate, including phantom Expense creations and participant/payer modifications. It does not cover Identity; the separate EUI witness/lock is mandatory even for no-op/rejection. No false-negative usage fact is synthesized from an unloaded or partial collection. Corrupt usage throws a technical facts anomaly.

The advisory hash can serialize unrelated Pots on collision; it cannot weaken correctness. It is a negative-existence guard, not a substitute for row CAS. Raw SQL maintenance, future writers and future Identity adapters must satisfy this lock contract before integration. No performance or SQL cost envelope is certified.

## Failure, retry and recovery

Attempt outcomes distinguish COMPLETED (with CHANGED/UNCHANGED/REJECTED), RETRY_SCHEDULED, QUARANTINED, CLAIM_LOST and RECOVERY_PENDING. Causes distinguish collision, technical failure, inconsistent facts and commit uncertainty. A version/authority collision is discarded, never converted directly into business obsolescence. Every new attempt reloads facts and reruns the pure transition using the immutable original observation.

After **confirmed rollback**, a short separate transaction locks the slot, checks the current token/deadline, and conditionally records one technical failure. It clears that claim, records retryAfter, or preserves the same slot as QUARANTINED at configurable N. Repeating an old token cannot count twice. Crash/expiry/commit uncertainty do not consume budget by assumption. No durable FAILED/UNKNOWN/PROCESSING state, slot purge or automatic quarantine reactivation is introduced. The fixture's administrative reset is an explicit expected-state update on the same slot, not a delivered administrative endpoint.

The owner requires one concrete datasource/manager, rejects ambient/nested ownership, and binds its actual ConnectionHolder/EntityManagerHolder identities. Every Command writer and all B public revision writers guard the unit. A caught SQL, JPA, validation, wrong-authority, independent-transaction or post-suffix writer failure poisons the physical owner. Standalone B caller transactions also receive rollback-only on revision failure. This is the sole semantic B production change; no domain transition, V1 SQL constraint, historical migration or writer_xid guard is weakened.

Rollback-on-commit-failure is enabled for the concrete manager. SQL rejection can then resolve by rollback. Transport SQLSTATE 08/40003 at COMMIT remains UNKNOWN even if a manager callback says rolled back (the server may have committed). Recovery reloads source/result/slot from WRITE: terminal plus original result/completion and no slot means completed once; pending means wait for resolution/conditional reacquisition. An uncertain failure-cession response is not blindly repeated. Both commit and cession acknowledgement loss are exercised by a PostgreSQL wire proxy reused from the V2 test mechanism, with fresh V3 assertions.

## Storage and external fixtures

Explicit Flyway locations: `classpath:db/v3-pot/migration` (unchanged B V1) **plus** `classpath:db/v3-pot-execution/migration` (C V2 extension). Fresh random schemas; not V2 baseline certification or a production upgrade. B-only bootstrap/catalogue remains unchanged. Command intent and provenance cannot be updated, terminal results/events/positions cannot be updated/deleted. The codec uses explicit operation names and exact canonical integer strings, never polymorphic Java class names, long rational conversion or floating point.

The production Identity V3 authority and acquisition/Discovery are not delivered. PostgreSQL authority rows and initial PENDING/slot/claim acquisition in the proof are **test fixtures** behind the explicit port. Their locks are real, not mocks. Initial attached-member business fixtures represent an attachment predating the tested command; their initial SQL seed is disclosed and does not implement or certify Invitation. Mutation/finalization/rollback/CAS/publication use the real C writers. No Identity/admission/consentment/Discovery end-to-end guarantee is inferred.

## Writer and transaction inventory (I-01)

- `ExecutePotCommand`: selective facts and pure domain transitions; no durable adapter/framework dependency.
- `PotCommand`, `PotCommandStore`, `PotActorAuthority`, `PotCommandTransactions`: four explicit pure contracts; nested values carry only their boundary's data. No universal command/transaction framework or Service per operation.
- `JdbcPotHistoryAdapter`: unchanged B snapshot/history/usage source; all decision reads happen before final transaction.
- `JdbcPotRevisionAdapter`: existing twelve typed methods, now guarded/poisoning; still no commit, request loading or retry. Exact xid8 remains mandatory.
- `JdbcPotCommandStore`: current/existence/slot protection, terminal result and slot removal, event prefix and closed dense suffix, conditional failure cession. Does not acquire new claims or publish Identity/READ effects.
- `PotCommandJdbcTransactions`: concrete Pot owner for snapshot and final WRITE; common JPA/JDBC identities, poison and resolution classification. No cross-base transaction.
- `PotCommandJson`: one explicit durable intention codec; does not normalize corrupt durable rationals.
- `V3PotCommandComposition`: actual explicit constructor assembly; no scanning/activation.
- Initial admission/claims, Identity row lifecycle and administrative reset: test-only fixtures. Generic V2 runtime writers and architecture exceptions remain inventoried historical consumers, not new V3 paths. Their removal belongs to conversion of those consumers; C adds no exception to A-01.

## Obligatory method/variant inventory

All methods below are declared in [L1 proof manifest](L1_Proof_Manifest.json), with fresh XML presence checked by the collector. This table fixes the expected variants and independent durable oracles before the final gates. Existing A/B methods are retained and rerun by their Maven closure.

| Methods | Applicable plan/SPEC-10 contribution and expected oracle |
|---|---|
| u01..u06 | Ordered orchestration; Changed revisions only; no-op/reject without revisions; collision one execution/no business rejection; claim loss no failure count; technical vs inconsistent/exhaustion; terminal replay no preparation/writes |
| c01 | Durable creations, Details/Financials/Weights/batches, no-op and obsolete equality, rights and referenced deletion, Expense then Shareholder then terminal Pot deletion; exact old snapshot retained; only effective components/global V advance (D01-K, D02-A..J/N/Q/R/S) |
| c02 | Same target two proposals at V2, winner V3, loser collision/rollback, new claim observes original V2 then obsolete rejection; no duplicate event (D02-K/M) |
| c03 | Details vs Weight; Financials vs Weight. New acquisition succeeds on compatible dependencies, fixed observation, final exact composed values (D02-B/D/F/L) |
| c04 | **Eight variants**: CreateExpense/UpdateFinancials × payer-only/participant-only × deletion-first/Expense-first. Loser first collides then re-evaluates; deletion-first rejects financial reference, Expense-first rejects deletion; no active reference to deleted Shareholder (D02-S transactional) |
| c05 | Prepared reference rejection loses validity when Expense is deleted; new acquisition deletes Shareholder; all old Financials and snapshot V3 unchanged |
| c06 | Prepared no-op loses authorization after attachment detaches; no stale success. Prepared forbidden rejection loses validity when fixture EUI actor changes; recalculated no-op succeeds |
| c07 | External authority revoke and negative-token rejection invalidated by fixture re-link; witness collision then recalculation; token of different EUI rejected |
| c08 | No-op vs same-component change becomes obsolete on reacquisition; DeletePot vs mutation becomes obsolete; mutation after winning DeletePot rejected, no child effect |
| c09 | Two finalizers same request/token one winner; terminal replay exact result; equivalent distinct request has its own rejection/result (D02-P) |
| c10 | **Five cuts** before version, after version, after revision, after finish, after publication; original business snapshot/positions preserved, PENDING/no result/slot retained; new claim succeeds |
| c11 | Real deferred SQL error at physical commit; full rollback, technical failure accounting, not terminal business rejection |
| c12 | Configurable N=3 fixture; retryAfter blocks acquisition, old token cannot double-count, Nth failure quarantines persistent PENDING slot; later command on same Pot progresses; explicit expected-state admin reset once then full attempt succeeds |
| c13 | **Two real wire faults** before COMMIT and after server COMMIT acknowledgement; independent durable observation proves zero/one effect, no speculative budget consumption or double effect |
| c14 | Actual PG lock wait; assert final transaction start < deadline < fresh DB clock before releasing the row (a stale now() would authorize); fresh post-lock DB clock rejects expiry, old token cannot finalize/count failure after takeover (C03-E/G/H) |
| c15 | Controlled expiry after valid final lock; actual takeover waits until physical commit, then sees no slot and cannot duplicate (C03-F, C04-C) |
| c16 | **Two variants** first publisher commits/rolls back; different Pot/EUI second publisher blocked on real counter lock, no higher durable position until resolution; dense continuation of pre-existing prefix (E01-A/B/C/E/F/H bounded two families) |
| c17 | JPA/JDBC identical PID/XID; caught validation errors and JPA errors in both relevant write orders rollback both; actual JPA-manager command finalization passes (C06-A/E/F/G) |
| c18 | Wrong manager/source rejected, ambient nested owner rejected, caught writer after closed suffix poisons whole command (C06-H/I) |
| c19 | >long rational and 1/8 codec round-trip, immutable observation, missing/null requestId SQL refused; negative Pot existence invalidated by concurrent CreatePot, then full recalculation succeeds |
| c20 | Injected independent REQUIRES_NEW writer detects changed ConnectionHolder and poisons outer unit even when caught (C06-I) |
| c21 | N-1=2 failures then success with distinct tokens; abandoned expired claim and stale failure accounting leave count unchanged |
| c22 | **No-op and rejection** hold external Identity lock through physical resolution; actual revoker waits; no mutation version for either decision |
| c23 | **Two wire faults on failure cession**: committed count once vs rollback count zero; no double accounting or blind cession retry; eventual normal reacquisition succeeds (C05-H) |
| c24 | Preparation fault cedes once; corrupt exhaustive usage cannot become false-negative deletion, is technical INCONSISTENT_FACTS; repaired fixture then correct referenced rejection |
| compositionUsesOneWriteManagerAndExplicitAuthority | Actual production composition factory + PostgreSQL + JpaTransactionManager; terminal/result/events/slot atomic; replay exact; wrong datasource refused |
| carch01..03 + existing A-01 | Pure orchestration/ports, owner guards on every writer, WRITE isolation, no independent transaction declaration in writer sources, explicit inactive composition, no production authority/mock fixture |

DOM-03 balance calculation, all Identity/Invitation families, real partition ownership/acquisition/Discovery, READ, Bruno, k6, SLOs and full CON/EVT/DOM certification remain outside this bounded proof. CON-06 READ scenarios N/O/P remain due at their lots. A passing internal gate cannot certify missing system paths.

## Post-audit L1-F (current correction to the protocol above)

INVALID_BINDING is prepared from the Identity witness alone, with no Pot version/history lookup. Finalization still protects claim, reloads durable intention and protects that witness, then finishes/publishes the rejection without protectPot. A changed Identity witness collides and is reacquired/re-evaluated normally. Other decisions retain the original Pot protection. Empty/duplicate multi-target intentions now fail construction; durable codec corruption remains explicitly INCONSISTENT_FACTS.

Before the closed publication suffix, `validateBusinessConstraints` executes one static SET CONSTRAINTS naming precisely the 18 deferred B FKs and four deferred B completeness triggers. It replaces catalogue discovery plus 22 separate statements. `v3_command_unit`, `v3_result_unit` and `v3_pot_event_request` remain deferred to physical commit. The catalogue equivalence test retains names/definitions/properties, and the injected FK failure retains technical classification and full rollback. No ALL IMMEDIATE, change to the owner or weakening of the final publication unit.

## L1-F2 — write-depth correction

The physical protocol, ports, claim/Identity/Pot lock order, CAS, xid8 ownership, owner poisoning, retry and closed publication suffix are unchanged. B preconditions now use direct bounded queries, including grouped multi-target checks; the exact closure uses revision_id and old bounds. V4 indexes exact candidate effects for changedComponents and the effect trigger, without changing their predicates or constraint timing. `verifyHistory` remains explicit outside finalization. [Invariant allocation](L1_F2_Invariant_Matrix.md), [fresh tests and performance](L1_F2_Closure_Report.md). L2 must preserve these local WRITE guarantees along with every transactional obligation above.
