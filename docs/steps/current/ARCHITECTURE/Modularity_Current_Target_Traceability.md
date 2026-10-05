# CURRENT → TARGET traceability at WP5

This file was absent at the requested path at the WP5 baseline `c592464922f0a2fb0550e19575ea816c1fcadd70`. It records the physical ownership established by WP5. The [TARGET topology](Modularity_Target_Topology.md) remains normative; the [WP5 execution report](Modularity_WP5_Execution_Report.md) contains the full pre-audit, test evidence and bridge ledger.

| Baseline CURRENT | WP5 disposition | TARGET owner |
| --- | --- | --- |
| `engine-command`, `engine-pot-command` | removed empty shells | `engine-consume-command`, `engine-write-pot` |
| `engine-registration` | removed facade and duplicate ports | `engine-admit-registration`, `engine-consume-registration`, PRIMARY |
| `engine-projection-task` | removed test shell; tests moved | `engine-consume-projection-task` |
| `engine-projection-balance`, `engine-projection-pot` | moved input loaders and historical balance calculation; removed POMs | `engine-consume-projection-task.input`, PRIMARY SQL adapters, `projector-pot` pure computation |
| `engine-core` | moved recorded Event values, SQL envelope and partitioner; removed POM | `domain-pot`, `infra-persistence-primary-jpa` |
| `infra-persistence-jpa` | renamed/repackaged, same migration bytes | `infra-persistence-primary-jpa` |
| `locator-consumption-command`, `locator-consumption-event`, `locator-consumption-binding` | removed empty shells; Event tests moved | capability supras, engines, PRIMARY, `orchestrator-poll-consumption` |
| `orchestrator-command-admission`, `binding-pot-command-spring` | removed empty shells | `engine-admit-command`, Command runtime composition |
| `infra-persistence-projection-jdbc`, `infra-persistence-read-jdbc`, `infra-projection-validation-networknt`, `infra-tx-spring` | kept | same TARGET modules |
| Eight firm runtimes | kept as composition roots | same TARGET runtime modules |
| `engine-processing-event`, `infra-read-persistence`, `locator-consumption-latest-known-version`, LKV runtime | kept provisional, pending `TBD-LKV` | no physical TARGET LKV owner selected |
| `domain-pot-projection`, `domain-projection-balance` | retained for D.24/WP6; empty POM shells | `domain-projection`, `domain-pot`, `projector-pot` after WP6 consolidation |

The retained LKV modules must not be counted as firm TARGET POMs. The WP6 domain shells are separate planned work. `TBD-COMMAND-CONTRACT` and `TBD-E2U` remain resolved; `TBD-LKV` remains open.
