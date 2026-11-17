# Rapport de clôture des 15 obligations bloquantes L7.3

- **Date** : 17 novembre 2026
- **Branche** : `feat/l7-i3-closure-and-consolidation` (issue de `main` @ `0227dd0f`, post-fusion PR #7)
- **Référentiel normatif** : [`L7_Residual_Certification_Matrix.md`](L7_Residual_Certification_Matrix.md) §3, [`L7_Termination_Plan.md`](L7_Termination_Plan.md) §1.1 et §§2.1–2.7, [`Reactor_Verification_Policy.md`](../testing/Reactor_Verification_Policy.md)
- **Verdict sur les 15 obligations bloquantes L7.3** : **`15 / 15` clôturées (`CURRENT_FULL`)** — aucune régression, aucune modification du moteur de projection ni du code productif applicatif.
- **Verdict global L7** : **L7 demeure `OPEN`** tant que la revue d'allocation des `338` `CURRENT_PARTIAL` + `30` `HISTORICAL_PARTIAL` L7.3, `41` `CURRENT_PARTIAL` L7.2 et les gates globales `L7.5` (`full` + `architecture-tests`) ne sont pas soldées.

---

## 1. Clôture par réemploi validé : `L2M-E02-J-REPORT`

- **Exigence** : interrompre effectivement le processus après admission HTTP durable et avant finalisation ; redémarrer puis vérifier unique résultat/effet/publication et aucun abandon (`OR-AT-UNIT`, `OR-RC-FOUR`).
- **Preuve réemployée** :
  - `L7-I4` [`e1-e3-06/receipt.json`](../testing/evidence/L7-I4-technical/e1-e3-06/receipt.json) (@ `230feb05`) :
    - `COMMAND` (`d6786a14-dfd6-471c-b3c3-93ba04849b30`) et `REGISTRATION` (`bb51233a-b979-4ce5-8472-d8bfb7572e00`) admis en `HTTP 202`.
    - Observation extérieure `PENDING` (`events=0, results=0, completions=0`) avant arrêt brutal.
    - `SIGKILL` effectif (`exitCode=137`, `runningAfterKill=false`, `pidAfterKill=0`), puis redémarrage (`runningAfterRestart=true`).
    - Reprise post-expiration du bail SQL (`238.173 s` → `238.522 s`) avec réconciliation WRITE/READ exacte (`1` résultat, `1` événement `POT` / `2` `IDENTITY`, `1` complétion, `0` doublon, `0` abandon, navigation vide autorisée avec le nouveau binding).
  - Complété sur `main` par `L7-LB` `S5-B` (`B2`–`B4`, `100 %` de rotation `owner_token`) et `S6` ([`s6-b6-recovery.json`](../testing/evidence/L7-LB-S6/s6-b6-recovery.json)).
- **Vérification d'équivalence (`230feb05..0227dd0f`)** :
  - `git log --oneline 230feb05..59f68956 -- app/infra-tx-spring/src/main app/infra-postgres-core/src/main app/infra-postgres-write/src/main app/orchestrator-consumption/src/main app/runtime-worker-process/src/main` ne montre que le renommage S1 (`38e5210f`), la consolidation SQL équivalente S3 (`85dba199`), la stabilisation `16dada44` et `05ae58f9` (qui **renforce** la garde `SpringAttemptTransactions` en rejetant un holder JDBC retenu avant tentative).
- **Statut** : **`CURRENT_FULL` (réemploi validé)**.

---

## 2. Clôture des 14 obligations par essais ciblés et audit sémantique (Étapes 2.1–2.7)

| Étape | Obligation(s) | Preuve produite / exécutée | Résultat | Statut |
|---|---|---|---|---|
| **2.1** | `L2M-E01-E` | [`L73TerminationWritePostgresTest.l2m_e01_e_commandUnchangedAndRejectedOnlyAdvanceCompletion`](../../app/infra-postgres-write/src/test/java/com/kartaguez/pocoma/infra/postgres/write/L73TerminationWritePostgresTest.java) + [`L73TerminationIdentityPostgresTest.l2m_e01_e_rejectedIdentityRequestOnlyAdvancesIdentityCompletion`](../../app/infra-postgres-write/src/test/java/com/kartaguez/pocoma/infra/postgres/write/identity/L73TerminationIdentityPostgresTest.java) | `Command UNCHANGED` et `REJECTED` n'avancent que `COMMAND_COMPLETION` ; `Identity REJECTED` n'avance que `IDENTITY_COMPLETION` ; aucune position de mutation consommée. | **`CURRENT_FULL`** |
| **2.2** | `L2M-CON-05-NOPURGE` | [`L73TerminationWritePostgresTest.l2m_con_05_nopurge_isolatedDeleteRefusedUnderRuntimeRolesAndGuard`](../../app/infra-postgres-write/src/test/java/com/kartaguez/pocoma/infra/postgres/write/L73TerminationWritePostgresTest.java) + audit `writer-inventory-final` | `DELETE FROM consumption_slot` refusé (`SQLSTATE 42501`) sous rôles `admission` et `reactivation` ; `JdbcConsumptionSlotStore.deleteProtected` refuse mauvais `claimToken` et état `QUARANTINED` ; slot conservé. | **`CURRENT_FULL`** |
| **2.3** | `L2M-C01-A-REPORT`, `L2M-C01-D-REPORT` | [`L73TerminationReadPostgresTest`](../../app/runtime-worker-materialize/src/test/java/com/kartaguez/pocoma/runtime/task/L73TerminationReadPostgresTest.java) | (a) Peuplement multipartition (`17` événements pertinents `EXTERNAL_USER_IDENTITY` sur `4` partitions) : ensemble exact des `consumption_slot` et `discovery_checkpoint = K` par partition ; (b) publication `Identity` retenue sur barrière transactionnelle couplée à `Discovery READ` avant et après commit et rollback. | **`CURRENT_FULL`** |
| **2.4** | `L2M-E01-G-INV-COMP`, `L2M-E01-G-ID-IDCOMP` | [`L73TerminationWritePostgresTest`](../../app/infra-postgres-write/src/test/java/com/kartaguez/pocoma/infra/postgres/write/L73TerminationWritePostgresTest.java) | Ordres forcés `INVITATION → COMMAND_COMPLETION` et `IDENTITY → IDENTITY_COMPLETION` (commit et rollback) attestés par `pg_blocking_pids`, `0` deadlock. | **`CURRENT_FULL`** |
| **2.5** | `L2M-CON-05-CRASH`, `L2M-C03-J-CRASH` | [`L73TerminationCrashAndCompositePostgresTest`](../../app/runtime-monolith/src/test/java/com/kartaguez/pocoma/L73TerminationCrashAndCompositePostgresTest.java) (`CrashChild` via `ProcessBuilder`) | `SIGKILL` (`destroyForcibly`) sur processus fils `WRITE` et `READ` (a) avec claim acquis sans cession (`attempt_count=0`, slot conservé, reprise post-expiration SQL du bail) ; (b) en écriture en vol (rollback physique PostgreSQL, `0` effet partiel, reprise propre). | **`CURRENT_FULL`** |
| **2.6** | `L2M-C04-L-REPORT`, `L2M-C06-M-REPORT`, `L2M-R8-03`, `L2M-R8-05` | Audit sémantique complet de [`writer-candidates.csv.gz`](../testing/evidence/L7-I4-technical/writer-inventory-final/writer-candidates.csv.gz) (`2 704` candidats : `410` `production`, `65` `tool-or-sql`, `2 229` `application-test`) + tests de refus 2.2 | Voir §3 ci-dessous : `1` seul `SLOT_DELETE` en production (`JdbcConsumptionSlotStore.deleteProtected`), `0` en `tool-or-sql`, toutes voies `DYNAMIC_SQL` / `CHECKPOINT_WRITE` / `DESTRUCTIVE_DDL` / `TRANSACTION_OWNER` attribuées et gardées. | **`CURRENT_FULL`** |
| **2.7** | `L2M-C04-K`, `L2M-E01-H-REPORT` | [`L73TerminationCrashAndCompositePostgresTest`](../../app/runtime-monolith/src/test/java/com/kartaguez/pocoma/L73TerminationCrashAndCompositePostgresTest.java) | (a) Corpus composite `Command` + `READ` (rollback + reprise, takeover + `CLAIM_LOST`/`COMMITTED`, réconciliation exacte `4` résultats / `4` événements / `4` complétions / `0` slot) ; (b) charge mixte concurrente `Pot` + `Identity` + `Invitation` avec rollback/retry : `5` familles denses `1..N`, `0` trou ni état partiel. | **`CURRENT_FULL`** |

---

## 3. Synthèse de l'audit sémantique `writer-inventory-final` (`2 704` candidats / `239` fichiers)

| Catégorie | `production` (`410`) | `tool-or-sql` (`65`) | `application-test` (`2 229`) | Conclusion d'audit |
|---|---:|---:|---:|---|
| `SLOT_DELETE` | `1` (`JdbcConsumptionSlotStore.java:316` `deleteProtected`) | `0` | `16` | **Conforme** : unique voie productive gardée par `claim_token`, `state='AVAILABLE'` et `SpringAttemptTransactions.guarded`. |
| `CHECKPOINT_WRITE` | `3` (`JdbcDiscoveryAdmissionStore.java:151` + 2 triggers `discovery_checkpoint_guard`) | `2` (`v3-rebuild-read.py:638`, `grant-l2-discovery.sql:55`) | `39` | **Conforme** : seules écritures autorisées = `Discovery` runtime et outil de reconstruction `READ` coordonné. |
| `DYNAMIC_SQL` | `34` (`18` fichiers : config `ProcessSettings`/`MaterializeSettings`, `BootstrapGuard`/`WriteSourceReadPrivileges` lecture seule, `JdbcReadMaintenanceAuthority` DDL maintenance gardée, `set constraints ... immediate`, tx `Spring*Transactions`, `V30`/`V31`/migrations, et 3 faux positifs `.execute()` Java) | `31` (`grant-l2-worker-dev.sql`, `grant-l5-admission.sql`) | `1 113` | **Conforme** : aucune construction SQL dynamique non bornée ni contournement de garde en production ou outillage. |
| `DESTRUCTIVE_DDL` | `55` (bootstrap, révocation privilèges, migrations) | `18` (`v3-rebuild-read.py`, `bootstrap-read-maintenance.sql`, `grant-*.sql`) | `54` | **Conforme** : strictement cantonné aux migrations, gardes d'isolation et maintenance `READ` coordonnée. |
| `FINALIZATION` | `45` (contrats/moteurs/métriques/triggers) | `14` (scripts perf) | `213` | **Conforme** : toutes finalisations rattachées au protocole `SlotCompletion` / `ConsumptionEngine`. |
| `TRANSACTION_OWNER` | `272` | `0` | `794` | **Conforme** : propriétaires `SpringAttemptTransactions` / `SpringReadTransactions` et compositions explicites, avec garde `05ae58f9` contre holder retenu. |

---

## 4. Périmètre de vérification déclaré et exécuté (`Reactor_Verification_Policy.md`)

- **Impact productif attendu / observé** : **AUCUN** (ajouts test-only dans `infra-postgres-write`, `runtime-worker-materialize`, `runtime-monolith` et documentation dans `docs/v3/`).
- **Slices déclarées** : `infra-postgres-write`, `runtime-worker-materialize` (`PROJECTION`), `runtime-monolith`.
- **Commandes exécutées** :
  - `cd app && ./mvnw -pl infra-postgres-write -Dtest=L73TerminationWritePostgresTest,L73TerminationIdentityPostgresTest test` → **`5/5` PASS** (`12.208 s`).
  - `cd app && ./mvnw -pl runtime-worker-materialize -Dtest=L73TerminationReadPostgresTest test` → **`2/2` PASS** (`8.703 s`).
  - `cd app && ./mvnw -pl runtime-monolith -Dtest=L73TerminationCrashAndCompositePostgresTest test` → **`4/4` PASS** (`21.090 s`).
- **Franchissement imprévu** : **AUCUN**.
- **Gate architecture globale / Réacteur complet** : **NON REQUIS** à cette étape (modifications test-only et documentaires ; la gate globale `L7.5` reste planifiée à la clôture finale de L7).
