# CURRENT → TARGET traceability after POST-WP6 closure

Status: **CURRENT = TARGET ACHIEVED** after the bounded POST-WP6 closure. The [WP6 execution report](Modularity_WP6_Execution_Report.md) remains historical evidence for WP6; the [POST-WP6 closure report](Modularity_Post_WP6_Closure_Report.md) carries the corrective proof required by the independent audit.

| CURRENT residue at WP6 baseline | Final TARGET owner | WP6 action |
| --- | --- | --- |
| LKV model, use case and write port in `engine-processing-event` | `engine-materialize-latest-known-version` | REHOME; legacy POM deleted |
| LKV candidate, durable Event reload, issue and failure policy in `locator-consumption-latest-known-version` | `supra-consume-lkv` | REHOME; locator POM deleted |
| LKV max-upsert plus shared READ bootstrap in `infra-read-persistence` | `infra-persistence-read-jdbc` | REHOME with identical migration bytes and classpath location; legacy POM deleted |
| independent LKV composition root | `runtime-latest-known-version-consumption-worker` | KEEP and rewire to final engine/supra/infra owners |
| empty `domain-pot-projection` shell | `domain-projection` and existing exact projection owners | DELETE |
| empty `domain-projection-balance` shell and tests | `domain-pot` for values/tests; `projector-pot` for pure projection | REHOME tests; DELETE shell |
| Event → ProjectionTask policy tests left under the mixed Event shell | `engine-produce-projection-task` | REHOME |

## Final architectural families

| Responsibility | Family | Boundary justification | Semantic owner |
| --- | --- | --- | --- |
| `engine-materialize-current-binding` | convergent current-state index, C2 | full ATTACHED/DETACHED payload and same-revision divergence policy | CURRENT_BINDING write side |
| `engine-read-current-binding` | READ | real GET Pot and self-service consumers | CURRENT_BINDING read side |
| `supra-consume-binding` + Binding runtime | Consumption specialization/runtime | Binding Fact discovery, reload and independent lifecycle | CURRENT_BINDING ingestion |
| `engine-materialize-latest-known-version` | convergent current-state index, C2 | scalar `PotId → max successfully consumed PotVersion`; gaps allowed | LKV write side |
| `supra-consume-lkv` + LKV runtime | Consumption specialization/runtime | durable Event candidate/reload and independent consumer lifecycle | LKV ingestion |
| `engine-consume-projection-task`, `projector-pot`, exact projection persistence/read | exact historical projection `@V` | each requested AUTH/READ_POT/POT_BALANCES key is immutable and versioned | ProjectionTask pipeline |

The common operational foundation is generic Consumption. No generic convergent-index engine, domain, port, orchestrator or repository exists. LKV has no read engine because it has no production read consumer.

## Final state

- Every production POM has a recognized architectural family and owner through its name and the final topology catalogue.
- `domain-consumption` exports only the explicit generic TARGET identities, slot lifecycle and claim lifecycle; its embedded compatibility API has been removed.
- Event processing/runtime has no production dependency on Command execution; the stale test-only POM dependency has been removed.
- No migration bridge, embedded compatibility cluster, locator POM, legacy shell, provisional physical boundary or architectural TBD remains.
- `CURRENT_BINDING !→ ProjectionTask`, `LKV !→ ProjectionTask`, and `ProjectionTask !→ LKV` are guarded.
- `Event processing/runtime !→ Command execution capability` and the approved `domain-consumption` record surface are guarded.
- SQL/schema/migration contents are unchanged.
