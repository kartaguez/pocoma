# Pocoma V3 — Clôture L7-C4 et Reprise de la Qualification Globale L7

- **Date de clôture** : 21 novembre 2026
- **Branche** : `feat/v3-l7` (base vérifiée à l'ouverture : `db65899b4d7af1e54de80879cbf674c0b819af98`)
- **Verdict L7-C4** : **`L7_C4_CLOSED`** *(sous réserves locales `C4-R12` et observation `a2-idle` post-inactivité)*
- **Statut de certification globale L7** : **`L7_GLOBAL_CERTIFICATION_DEFERRED`** *(reprise documentée et vérifiée par tranches ; gates globales `L7.5` et revue `R1` des partielles restantes à exécuter)*

---

## 1. Clôture du chantier L7-C4

Les décisions techniques issues de la Phase 2 ([phase2-optimization-report.md](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/v3/L7_C4/phase2-optimization-report.md)) sont conservées sans réouverture d'optimisation :

1. **Optimisations intrinsèques du polling (`B1–B3`) conservées** (`f314ed9d050951b2fc90b0b3d2e0a5082fd5d900`) :
   - `B1` : Saut de `effects.write` sur fenêtre Discovery vide (`discover(lease, false)`) et suivi *dirty/clean* par paire `(consumptionId, partitionId)` dans `ConsumptionLoop` (réveillé sur admission/fin d'enveloppe, réconcilié toutes les `≤ 500 ms`).
   - `B2` : Cache de `discovery_checkpoint` par incarnation de lease (`ownerToken`) dans `DiscoveryEngine`, invalidé sur changement de propriétaire ou `STALE_CHECKPOINT`.
   - `B3` : Déduplication intra-tour des sondes `(ConsumableFamily, checkpoint)` déjà constatées vides dans `DiscoveryLoop`.
2. **Rééquilibrage des ressources `A2` conservé** (`db65899b4d7af1e54de80879cbf674c0b819af98`) :
   - Profil `6,0` CPUs / `5376m` RAM à budget constant (`postgres=2,0`, `http-write=0,5`, `http-read=0,75`, `command-worker=0,75`, `results-worker=0,75`, `business-read-worker=1,25`).
   - `worker-limit=4` avec quotas réservés (`Command=3, IdentityRequest=2` ; `MaterializeCommandResult=3, MaterializeIdentityRequestResult=2`) et pools HikariCP alignés (`6` business/source-read, `10` control) dans [docker/v3-five-runtimes/](file:///Users/julien.guezennec/Dev/projects/pocoma/docker/v3-five-runtimes/).
3. **Topologie à 5 runtimes conservée** (`http-write`, `http-read`, `command-worker`, `results-worker`, `business-read-worker`). Les variantes `topo-c1` / `topo-c2` restent écartées (`ProcessSettings` / `MaterializeSettings`).
4. **Contrats transactionnels C3 inchangés** : aucune modification de [ReadAvailability.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/infra-postgres-read/src/main/java/com/kartaguez/pocoma/infra/postgres/read/ReadAvailability.java) ni de `V5__projection_availability.sql`.
5. **Réveil événementiel (Axe 5)** : documenté dans [event-driven-wakeup-study.md](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/v3/L7_C4/event-driven-wakeup-study.md), non implémenté.

---

## 2. Qualification explicite des optimisations `B1–B3`

### 2.1 Couverture des mécanismes critiques (unitaires + PostgreSQL 17 réel)

| Exigence vérifiée | Preuve exécutée | Résultat |
|---|---|---|
| **Arrivée d'un nouvel événement après inactivité & réveil interne / réconciliation (`≤ 500 ms`)** | [L7C4PollingOptimizationTest.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/supra-polling-consumption/src/test/java/com/kartaguez/pocoma/supra/polling/consumption/L7C4PollingOptimizationTest.java) (`b1_consumptionLoopSkipsCleanPairsAndWakesOnDiscoveryOrReconcile`) | **`PASS`** |
| **Déduplication intra-tour sans perte entre familles/partitions (`B3`)** | `L7C4PollingOptimizationTest.b3_discoveryLoopDeduplicatesSameFamilyEmptyCheckpointsWithinTour` | **`PASS`** |
| **Invalidation du cache checkpoint sur changement de propriétaire (`ownerToken`) et `STALE_CHECKPOINT` (`B2`)** | [DiscoveryEngineTest.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/orchestrator-consumption/src/test/java/com/kartaguez/pocoma/orchestrator/consumption/DiscoveryEngineTest.java) (`b2_checkpointCacheHitsSameOwnerAndInvalidatesOnOwnerChangeOrStale`) | **`39/39 PASS`** |
| **Expiration de leases, fencing, changement de propriétaire, concurrence multi-instances sur PostgreSQL 17 réel** | `runtime-worker-process`: `WorkerResourcesPostgresTest` (`13/13`), `WorkerCompositionPostgresTest` (`7/7`), `DiscoveryCompositionPostgresTest`, `ConsumptionLifecycleCompositionPostgresTest`, `WorkerPostgresTest` | **`PASS`** |
| **Interruptions, redémarrages (`EOFException`/crash JVM) et reprises READ/WRITE** | `runtime-worker-materialize` (`50/50`), `runtime-monolith`: `L73TerminationWritePostgresTest` (`4/4`), `L73TerminationCrashAndCompositePostgresTest` (`4/4`), `ReadWorkerResourcesPostgresTest` (`2/2`) | **`PASS`** |
| **Contrats Discovery (`discover(lease)` 1-arg vs `discover(lease, false)`)** | [ConsumptionWorkerTest.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/compose-polling-burnable-write-postgres/src/test/java/com/kartaguez/pocoma/supra/polling/consumption/ConsumptionWorkerTest.java) (`13/13`) + `ReadinessOracleTest` (`13/13`) | **`PASS`** |

### 2.2 Délais de découverte après inactivité sur PostgreSQL réel (`SPEC-09`)

Relevé exact extrait de [phase2-summary.json](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L7-C4/phase2/phase2-summary.json) :

| Campagne | Échantillons (`n`) | `admissionMs` (`p50` / `p95=max`) | `resultE2eMs` (`p50` / `p95=max`) | `c03E2eMs` (`p50` / `p95=max`) | Lecture technique |
|---|---:|---|---|---|
| **`a2-idle` (`r1..r3`, après 60 s d'inactivité à `0,33 cmd/s`)** | `10` / run | `17,70–20,63` / `397,63–603,09 ms` | `100,61–114,85` / `832,23–984,30 ms` | **`142,06–148,98`** / **`977,57–1 120,57 ms`** | Médiane conforme (`≤ 500 ms`). Le `p95=max` (`n=10`) correspond au premier appel à froid (warmup HTTP/HikariCP/JIT, dont `398–603 ms` dès l'admission), pas au plafond `500 ms` de réconciliation `B1`. |
| **`a2-med1` (`r1..r3`, régime établi `1 cmd/s`)** | `60` / run | `15,26–15,99` / `20,73–22,87 ms` | `94,70–98,87` / `122,81–130,95 ms` | **`119,46–125,43`** / **`146,11–160,38 ms`** (`max ≤ 207,73 ms`) | Latence soutenue très inférieure aux seuils `SPEC-09` (`p50 ≤ 500 ms`, `p95 ≤ 1 000 ms`). |

### 2.3 Rapprochement des exécutions finales `B3 + A2`

Le rapport Phase 2 ([phase2-optimization-report.md](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/v3/L7_C4/phase2-optimization-report.md#L99)) mentionnait `14/14` exécutions de confirmation (correspondant au dernier lot groupé). Le dépouillement direct de l'archive brute [phase2-summary.json](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L7-C4/phase2/phase2-summary.json) (`a2Confirmation`) atteste **16/16 exécutions `PASS`** (`invariantViolations = 0`, `read_availability_valid = true`) :
- `a2-idle` : `3/3` (`passRuns = 3`)
- `a2-med1` : `3/3` (`passRuns = 3`)
- `a2-id1` : `3/3` (`passRuns = 3`)
- `a2-ramp` : `3/3` (`passRuns = 3`)
- `a2-pot-vs-id` : `3/3` (`passRuns = 3`)
- `a2-backlog-10k` : `1/1` (`passRuns = 1`)
- Plus `13` exécutions de progression dans `progression` (`b1-*`, `b2-*`, `b3-*`, `a1-*`).

---

## 3. Matrice actualisée des obligations L7

Référentiels relus : [L7_Termination_Plan.md](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/v3/L7_Termination_Plan.md), [L7_Residual_Certification_Matrix.md](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/v3/L7_Residual_Certification_Matrix.md), [L7_Reservations_Register_Updated.md](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/v3/L7_Reservations_Register_Updated.md), [SPEC-09_Performance.md](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/v3/SPEC-09_Performance.md), [SPEC-10_Test_Certification.md](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/v3/SPEC-10_Test_Certification.md) et [Reactor_Verification_Policy.md](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/Reactor_Verification_Policy.md).

Sur les **522** lignes de la matrice résiduelle L7 :

| Périmètre | État dans la matrice | Impact C1–C4 et statut actualisé |
|---|---|---|
| **Fondations `L7.1` / contrats cœur** (`74 CURRENT_FULL`, `5 HISTORICAL_REUSABLE`) | Revalidés par construction ou tests ciblés | **Réutilisables et revalidés** sur `orchestrator-consumption`, `supra-polling-consumption`, `compose-polling-*`, `architecture-tests` (`52/52`). |
| **Chantiers `C1` (incrémental `N−m`), `C2` (Radix16/PotBalance), `C3` (`V5` ReadAvailability)** | `V5` et stockage READ inchangés en C4 | **Preuves C1–C3 réutilisables** ; invariants `L7.3` et `ReadAvailability` rejoués en C4 sur `runtime-worker-materialize` (`50/50`) et `runtime-monolith` (`10/10`) -> **`PASS`**. |
| **Mécanismes polling/ressources impactés par C4 (`B1–B3` + `A2`)** | Invalidés par `f314ed9d` / `db65899b` | **Rejoués et requalifiés** (tranches `COMMAND`, `PROJECTION`, `MONOLITH`, `architecture-tests`, scripts Python `operations`/`l7_4a`). |
| **Obligations `PARTIAL` (`379 CURRENT_PARTIAL` + `49 HISTORICAL_PARTIAL`, dont `41` L7.2 et `338+30` L7.3)** | Non soldées dans la matrice (`R1`) | **Restent `PARTIAL`** — revue systématique du dossier `R1` requise avant clôture L7. |
| **Charge HTTP & Queue froide (`DEBT-L7LB-01` / `RES-L7LB-S6-01`, `R2`)** | Ouvert en `L7_Reservations_Register_Updated.md` | **Reste ouvert** (y compris suivi du premier pic à froid `a2-idle` `p95=978–1121 ms` et rééquilibre `/api/v3` vs `/v3`). |
| **Gates globales `L7.5` (`CERT-FULL`, `CERT-ARCH`, `CERT-SPEC10`, `CERT-AUDIT`, `CERT-TRACE`)** | Exigées en fin de L7 (`RES-L75-GATES`) | **`CERT-ARCH` (`52/52`) `PASS`** ; **`CERT-FULL` (`./mvnw test` réacteur complet non filtré sur SHA gelé) `NOT_RUN`** selon `Reactor_Verification_Policy.md`. |
| **Qualification `SPEC-09` Config A/B (`2×4 vCPU`) et `L74-ENV-01` (`10k/1M`, `100k/10M`, HA/TLS)** | Différé (`R3` / `RES-L8-PROD`) | **Reste différé hors poste local Mac** (`C4-R12`). |

---

## 4. Vérifications exécutées pendant la clôture C4

Conformément à [Reactor_Verification_Policy.md](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/Reactor_Verification_Policy.md), aucune exécution aveugle du réacteur complet n'a été lancée par défaut ; les tranches impactées ont été déclarées et vérifiées explicitement :

| Tranche / Cible | Commande exécutée (depuis `app/` sauf mention) | Résultat exact |
|---|---|---|
| **Unitaires `orchestrator-consumption`** | `./mvnw --batch-mode --no-transfer-progress -pl orchestrator-consumption test` | **`39/39 PASS`** (`0` échec, `0` skipped) |
| **Unitaires `supra-polling-consumption`** | `./mvnw --batch-mode --no-transfer-progress -pl supra-polling-consumption -am test` | **`2/2 PASS`** (`L7C4PollingOptimizationTest`) + **`1/1 PASS`** (`RoutingV1Test`) |
| **Gate architecture (`CERT-ARCH`)** | `./mvnw --batch-mode --no-transfer-progress -pl architecture-tests test` | **`52/52 PASS`** |
| **Tranche `COMMAND` (PostgreSQL 17)** | `./mvnw --batch-mode --no-transfer-progress -pl runtime-worker-process -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=WorkerResourcesPostgresTest,WorkerCompositionPostgresTest clean test` | **`20/20 PASS`** (`WorkerResourcesPostgresTest` `13/13`, `WorkerCompositionPostgresTest` `7/7`, `06:11 min`) *(suite au passage préalable de `DiscoveryCompositionPostgresTest`, `ConsumptionLifecycleCompositionPostgresTest`, `WorkerPostgresTest`)* |
| **Tranche `PROJECTION` (PostgreSQL 17)** | `./mvnw --batch-mode --no-transfer-progress -pl runtime-worker-materialize -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=L73TerminationReadPostgresTest,ReadParticipantsIntegrationPostgresTest,ReadRuntimeAssemblyPostgresTest,ReadQuotaPostgresTest,CommandResultAvailabilityPostgresTest,ReadAvailabilityPostgresTest test` | **`50/50 PASS`** (`03:19 min`) |
| **Tranche `MONOLITH` + `infra-postgres-write` (PostgreSQL 17)** | `./mvnw --batch-mode --no-transfer-progress -pl runtime-monolith -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=L73TerminationWritePostgresTest,L73TerminationCrashAndCompositePostgresTest,ReadWorkerResourcesPostgresTest test` | **`10/10 PASS`** (`4/4` `L73TerminationWritePostgresTest` + `6/6` dans `runtime-monolith`, `10:09 min`) |
| **Composition polling + amont (29 modules dont `infra-postgres-write/read`)** | `./mvnw --batch-mode --no-transfer-progress -pl compose-polling-burnable-write-postgres,compose-polling-nonburnable-materialize-postgres -am -Dsurefire.failIfNoSpecifiedTests=false test` puis relance ciblée `-pl compose-polling-burnable-write-postgres,compose-polling-nonburnable-materialize-postgres test` après correction de `ConsumptionWorkerTest` | **`26/26 PASS`** sur les 2 modules (`13/13` `ConsumptionWorkerTest` + `13/13` `ReadinessOracleTest`) ; les 28 modules amont ont tous passé dans le run `-am` (`18:10 min`) |
| **Outillage & baseline `.properties` `A2`** | `python3 -B scripts/operations/test_v3_operations.py && python3 -B scripts/performance/test_l7_4a_baseline.py` | **`7/7 PASS`** + **`4/4 PASS`** |

---

## 5. Écarts détectés, corrections de tests et réserves restantes

Deux tests obsolètes vis-à-vis du comportement légitime `B1–B3` ont été détectés et corrigés **sans modification du code de production** :
1. [WorkerResourcesPostgresTest.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/runtime-worker-process/src/test/java/com/kartaguez/pocoma/runtime/command/WorkerResourcesPostgresTest.java#L1493-L1495) (`i5_r02` / `controlMaximum`) : après `armed.set(true)`, le scénario `write` avait déjà admis les 4 commandes avant démarrage, laissant une fenêtre Discovery vide que `B1` (`discover(lease, false)`) saute avant `admissions.liveSlots()`. L'insertion d'une commande en attente (`pendingAtPartition(f, 61400, 0)`) rétablit l'entrée attendue dans `liveSlots()`.
2. [ConsumptionWorkerTest.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/compose-polling-burnable-write-postgres/src/test/java/com/kartaguez/pocoma/supra/polling/consumption/ConsumptionWorkerTest.java#L1267-L1274) (`i5_u11_discovery_checkpoints`, `i5_u12_discovery_negatives`) : l'assertion `journal` héritée attendait `"window:" + advanced.get()` alors que `window:` trace l'offset de fenêtre (`window:3`) et que `B1`/`B3` omettent `owner-return` sur fenêtre vide ou dédupliquée. L'assertion accepte désormais `"owner-return"`, `"window:..."` ou `"metric:..."`.

### Réserves bloquantes pour la certification globale L7
- **`R1` (Partielles L7.2/L7.3)** : `41` lignes L7.2 et `338+30` lignes L7.3 encore marquées `PARTIAL` dans la matrice résiduelle.
- **`R2` (`DEBT-L7LB-01` & cold-tail `a2-idle`)** : pic à froid `p95=978–1121 ms` sur le premier appel après 60 s d'inactivité et réserves LB.
- **`RES-L75-GATES`** : exécution du réacteur complet non filtré (`./mvnw test` sur les 42 modules) et manifeste de provenance final sur SHA gelé non encore exécutés.
- **`L74-ENV-01` / `RES-L8-PROD`** : campagne `SPEC-09` Config A/B sur cible `2×4 vCPU` et volumes `10k/1M` / `100k/10M` différées hors poste local Mac.

---

## 6. Statut réel de certification globale L7

- **L7-C4** : **`L7_C4_CLOSED`** *(objectifs C4 atteints et vérifiés sur PostgreSQL 17 et E2E `16/16 PASS`)*.
- **Qualification globale L7** : **`L7_GLOBAL_CERTIFICATION_DEFERRED`** *(en attente de la revue `R1`, de la clôture `R2` et du gate `L7.5` `CERT-FULL` sur SHA gelé, avec `SPEC-09` Config A/B différé vers L8 / cible)*.
