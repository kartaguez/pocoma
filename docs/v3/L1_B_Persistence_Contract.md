# L1-B Pot persistence contract

This is the internal V3 WRITE source and revision boundary. It is not a Command execution service, a READ projection, an activated runtime, or a migration path from V2. See [execution report](L1_AB_Execution_Report.md) and [approved plan](L1_Design_and_Verification_Plan.md), §§15–17, 22–24. Independent audit is required before C.

## Physical model and bootstrap

Use Flyway **only** at `classpath:db/v3-pot/migration` on a fresh development schema, with the data source's `currentSchema` set explicitly. The tests use random schemas inside a disposable PostgreSQL container. No Spring application scan, `ddl-auto=create`, external database migration, V2 baseline certification, or modification of V2 PRIMARY V1–V31 / READ V1–V14 occurs through this bootstrap.

`v3_pot_identity` is stable. `v3_pot_version` records each global version and an application-supplied instant. Child identity tables retain their Pot owner and first global version of existence. Eight component tables correspond exactly to PotHeader/Details, ShareholderHeader/Details/Weight and ExpenseHeader/Details/Financials. Every revision has its own technical identity, stable object identity and owner, positive local version, and `[active_from,active_to)` in global versions. Unique keys protect local revisions and at most one open revision. Readers and writers validate complete chains: initial revision 1 at birth, contiguous local increments and intervals, one final open revision, no gap or overlap. Every endpoint references real version metadata.

`v3_expense_participant` belongs to a precise Financials revision. Its foreign keys reference stable Shareholder identities in the **same Pot**. The payer can be outside the participant set. Both weight integers are NULL when the override is absent; otherwise both are positive canonical integer text. No cascade deletion exists. Deleted objects retain all values and intervals; only their Header changes.

Amount and weight pairs are **text**, without bigint or numeric precision ceilings. SQL validates lexical syntax/sign/nullity; adapters additionally reject non-reduced pairs instead of normalizing corrupt storage. Domain normalization happens before storage. Optional overrides, default weights, amounts beyond `long`, exact decimals and canonical zero are exercised on PostgreSQL.

Deferred constraints reject committing a version with no component revision and committing an identity without all initial components at the same V/local revision 1. They allow the caller to write metadata and components inside one transaction. C must force deferred checks before its event-sequencing suffix, in addition to its normal flush/validation obligations.

## Read boundary

`PotHistoryPort.atVersion(P,V)` reconstructs directly at V, including terminal/deleted Headers, without consulting V−1 or substituting current values. Complete snapshots load all eight kinds and participants in grouped queries. `componentsAtVersion` loads selected historical contents; `current` selects the latest snapshot. Selection is explicit and immutable. `ComponentRead.NotLoaded`, `.Absent` and `.Present` are distinct. Absence means no object at V; a known identity with a required missing component is a technical integrity failure.

All public reads require the caller's transaction on the same WRITE data source, at REPEATABLE_READ or SERIALIZABLE. This keeps metadata, component selection, authorization and reference facts on one snapshot during a concurrent commit. The adapter starts no independent transaction. Current authorization loads creator/active user attachment facts; unrequested names, weights and Expense payloads are omitted. Historical facts are facts **at V**, not authority to execute a current mutation.

`activeUsage(P,V,S)` exhaustively examines payer and participant references of every active Expense. It never derives absence from a page or from deleted Expenses. It checks the relevant histories/reference integrity before producing a negative fact. Deleted Expenses retain their historical Financials and do not block current Shareholder deletion. Reader query counts remain constant with Expense count in the bounded fixture (1 versus 21); the evidence is not a latency/SLO certification. Full-chain validation has a history-volume cost and no performance envelope is certified here.

## Revision boundary and C handoff

Adapters are explicitly constructed with the caller's WRITE `DataSource`; there is no component scan activation. Writers require a caller-owned, writable physical transaction bound to that source. They never commit, retry, create a transaction, publish an event, validate a durable request, or arbitrate global CAS.

`createPot` consumes the domain's initial Changed proposal (expected 0, candidate 1). For another mutation, **C first protects and arbitrates V**, then the caller invokes `recordVersion(P,V,V+1,instant)` and every relevant typed revision method in the same transaction. Creations insert all initial components. Revisions close only the changed component and insert its exact local successor at V+1. The writer validates subject, owner, expected local version and interval chain before closing anything. Batch validation precedes writes. Any technical failure requires rollback of the entire caller transaction, including one caught by a higher layer.

A candidate's metadata must have been inserted in the current physical PostgreSQL transaction. Its technical `writer_xid` is a full native `xid8`, defaulted by PostgreSQL to `pg_current_xact_id()`, and compared exactly with that transaction's identifier. This prevents a delayed proposal from adding a component to a **previously committed** global version, even when that component was otherwise unchanged. Retaining the full epoch avoids aliasing on 32-bit transaction-ID wraparound; the regression fixture distinguishes identifiers differing by 2^32 despite equal low bits. This is a revision-integrity guard, not C's CAS/claim/authority protection. Ordinary DataSourceTransactionManager and JpaTransactionManager paths are tested; no generalized nested/distributed transaction claim is made. No transaction identifier leaks into the domain or ports.

B proves rollback and physical compatibility with those two managers. It does **not** prove request/claim fencing, authority stability until commit, collision retry, no-op/rejection finalization, result/event/slot atomicity, or event sequencing. Those remain C. No C orchestration has been started.

## Inventory and reuse (I-01)

Reuse: stable domain identifiers/text values, existing module graph, interval predicate, explicit transaction composition knowledge. Replace: V2 grouped mutable rows, long rational columns, implicit mandatory share weights, and per-Expense participant reads. New code: three internal port files, two concrete JDBC adapters plus a package-private SQL helper, one V3 migration, one PostgreSQL proof suite. The helper centralizes bound SQL and chain checks; typed domain transitions remain in `domain-pot`, with no universal mutation registry/framework or new mapper/DTO/service layer.

Existing V2 readers/writers/projections continue to serve only their historical runtime. Their retirement and the complete writer-path inventory remain due in C/READ/Consumption. I-01 is bounded to A/B here. A-01's exact V2 port exceptions expire as their owning contracts migrate: Pot contexts in C, command discovery/outcome contracts in Command/Consumption, ordering contracts in Projection/READ, admission models in Supra WRITE; each migration must remove/reassess its exceptions.
