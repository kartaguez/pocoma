# L7-C4 — Diagnostic du débit utile, des facteurs limitants et de l'organisation des runtimes de workers

- **Date de cadrage** : 21 novembre 2026
- **Branche de référence** : `feat/v3-l7`
- **HEAD vérifié à l'ouverture** : `181904cd02ffb7e3b9b60cf754cf365b99c2a3fa` (`origin/feat/v3-l7`, arbre propre, divergence `0/0`)
- **Statut du chantier** : **`L7_C4_PLAN_READY_FOR_PHASE_1`** (cadrage documentaire achevé ; aucune campagne exécutée, aucune modification de code, SQL ou configuration engagée)
- **État des chantiers amont** :
  - **L7-C1 (`L7.4-A3+A4`)** : Clos (`A3_A4_READY_TO_PUBLISH`, [rapport de consolidation](../L7_4_A3_A4_Consolidated_Qualification_Report.md)).
  - **L7-C2 (`C2-B1..B3`)** : Clos (`L7_C2_CLOSED` @ `d2530925`, [rapport consolidé C2-B3](../L7_C2_B3_Storage_Consolidated_Qualification_Report.md)).
  - **L7-C3 (`V5 Availability`)** : Clos avec réserves (`L7_C3_CONSOLIDATION_PASS_WITH_RESERVATIONS` @ `2181bd21`, [dossier L7-C3](../L7_C3/README.md) · [consolidation](../L7_C3/consolidation.md)).
  - **Certification globale L7** : Différée ([plan de terminaison L7](../L7_Termination_Plan.md)).

> [!IMPORTANT]
> **Distinction de périmètre** : Le chantier **L7-C4** est consacré au diagnostic causal du débit utile, des interférences entre familles de consommations et de l'organisation/dimensionnement des runtimes de workers après C1, C2 et C3. Il ne doit pas être confondu avec le lot historique **L7.4** ([`L7_I4_SPEC09_Technical_Qualification_Report.md`](../L7_I4_SPEC09_Technical_Qualification_Report.md)), dédié à la qualification de conformité contractuelle `SPEC-09`.

---

## 1. Mission et finalité du chantier L7-C4

Après les gains algorithmiques de matérialisation incrémentale `N−m` (**C1**), la réduction `10.17×` de l'empreinte disque/TOAST et du WAL de stockage READ (**C2**), et l'introduction du contrat transactionnel de publication de disponibilité à 8 familles en `v3_read` (**C3**), le profil de coût de Pocoma V3 s'est déplacé :
- Le temps de calcul métier pur des projections (`PotVersion`, `PotBalance`, `PotVisibility`) ne représente plus qu'une fraction minoritaire du CPU des workers (`~4.2 %` du CPU de `business-read-worker` sur `restore-large-1` en C2-B3) et environ `29 %` de la latence E2E `C-03` sur `med-5`.
- Les facteurs dominants observés en C2-B3 et C3 résident désormais dans l'ordonnancement de polling/découverte (`~8 650 TPS` de contrôle, `96.5k` acquisitions `read-control` en `60 s`), la cohabitation de 5 projections hétérogènes au sein de `business-read-worker` (`M_W = 3`), et la sérialisation transactionnelle des compteurs C3 par famille en fin de transaction READ.

Le chantier **L7-C4** a pour mission :
1. **D'identifier les limitations réelles** du débit utile et des latences E2E (`WRITE → READ`) de Pocoma V3 sur le code C3 consolidé.
2. **De comprendre et quantifier les interférences** entre les 9 familles de consommations (polling à vide, partage de `WorkerCapacity`, pools HikariCP, verrous PostgreSQL).
3. **D'étudier les regroupements statiques** des consommations en runtimes de workers distincts, sans orchestrateur dynamique.
4. **De comparer dimensionnement vertical** (CPU/RAM, `worker-limit`, `consumption-limits`, pools JDBC) **et réplication horizontale** (plusieurs instances sur les 36 partitions logiques fixes) à budget matériel constant.
5. **De protéger la capacité nécessaire aux parcours utilisateur critiques** (`SPEC-09` : `C-03`, `PotBalance`, `Identity/Registration`, résultats terminaux).
6. **De rechercher une topologie efficace et sobre en ressources**, en optimisant d'abord à budget global constant puis en identifiant le dimensionnement minimal satisfaisant les objectifs.

Le chantier est structuré en **deux phases séquentielles séparées par une Gate de décision humaine formelle (`G-C4-1`)** :
- **Phase 1 — Diagnostic causal et établissement de la référence post-C3** (aucune modification de production).
- **Gate `G-C4-1`** — Revue contradictoire du dossier de diagnostic et arbitrage humain explicite par famille d'optimisations.
- **Phase 2 — Expérimentation contrôlée d'optimisations et recherche de sobriété** (uniquement sur les axes autorisés à la Gate `G-C4-1`).

---

## 2. Dossier documentaire L7-C4

| Livrable | Fichier | Rôle normatif dans le chantier C4 |
|---|---|---|
| **0. Index & Synthèse** | [`README.md`](README.md) | Point d'entrée du dossier L7-C4, synthèse de l'état technique vérifié au HEAD `181904cd` et gouvernance du chantier. |
| **1. Plan directeur C4** | [`plan.md`](plan.md) | Phases 1 et 2, dépendances, invariants non négociables, périmètre de vérification (`Reactor_Verification_Policy.md`), gates et critères de sortie. |
| **2. Protocole de diagnostic** | [`diagnostic-protocol.md`](diagnostic-protocol.md) | Métrologie à 2 niveaux (E2E + interne), campagnes `C4-P1-C1..C5`, protocole dédié à la réserve C3 (`98.0 → 82.6 final./s` et WAL `XLARGE`), maîtrise du laboratoire local et outillage manquant. |
| **3. Matrice de traçabilité** | [`traceability-matrix.md`](traceability-matrix.md) | Correspondance bijective entre arbitrages `C4-R1..R13`, exigences `SPEC-04/05/08/09/10`, risques techniques, campagnes et preuves attendues. |
| **4. Registre des réserves et hypothèses** | [`reservations-and-hypotheses.md`](reservations-and-hypotheses.md) | Distinction stricte entre faits établis dans le code (`F-*`), observations historiques attribuées (`O-*`), réserves héritées (`RES-*`), hypothèses à tester (`H-*`) et questions ouvertes (`Q-*`). |
| **5. Gabarit de rapport de Gate 1 → 2** | [`gate-decision-template.md`](gate-decision-template.md) | Format normatif du dossier de synthèse de Phase 1 et formulaire d'autorisation/refus humain par famille d'interventions pour la Gate `G-C4-1`. |

---

## 3. Synthèse de l'état technique vérifié sur le code courant (`181904cd`)

L'audit statique du code et des configurations au HEAD `181904cd02ffb7e3b9b60cf754cf365b99c2a3fa` établit l'inventaire exact suivant :

### 3.1 Les 9 consommations, leurs familles sources et les 36 partitions logiques

Définies dans [`ConsumptionCatalog.java`](../../../app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/ConsumptionCatalog.java#L8-L45), routées par [`RoutingV1.java`](../../../app/domain-consumption/src/main/java/com/kartaguez/pocoma/domain/consumption/RoutingV1.java#L13-L36) (`sha256-v1`, 4 partitions `0..3` par consommation) et initialisées par [`bootstrap-l2-routing.sql`](../../../scripts/v3/bootstrap-l2-routing.sql#L25-L53) :

| # | Nom canonique | `consumption_id` | `ConsumableFamily` | Autorité | Type | Partitions | Runtime d'accueil actuel | Publication C3 (`ReadAvailability.Family`) |
|---:|---|---|---|---|---|---:|---|---|
| 1 | `Command` | `98f7268d-d340-581c-b83d-010926223e14` | `COMMAND` | `WRITE` | Burnable | `0..3` (4) | `command-worker` | *(publication WRITE : `POT_EVENT`, `INVITATION_EVENT`, `COMMAND_COMPLETION_EVENT`)* |
| 2 | `IdentityRequest` | `2999e75c-272d-5afe-a966-c9364ef021fe` | `IDENTITY_REQUEST` | `WRITE` | Burnable | `0..3` (4) | `command-worker` | *(publication WRITE : `IDENTITY_EVENT`, `IDENTITY_COMPLETION_EVENT`)* |
| 3 | `MaterializePotVersion` | `3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a` | `POT_EVENT` | `READ` | Non-burnable | `0..3` (4) | `business-read-worker` | `POT_VERSION_AVAILABLE` (+ `CURRENT_POT_CHANGED` si avance) |
| 4 | `MaterializePotBalance` | `190a93c6-b8af-5ac7-8001-72e2ec53c2df` | `POT_EVENT` | `READ` | Non-burnable | `0..3` (4) | `business-read-worker` | `POT_BALANCE_AVAILABLE` *(sauf `DeletePot`)* |
| 5 | `MaterializePotVisibility` | `b4d04cce-b5da-5d1b-9698-5ff9af9a46fa` | `POT_EVENT` | `READ` | Non-burnable | `0..3` (4) | `business-read-worker` | `POT_VISIBILITY_CHANGED` |
| 6 | `MaterializeInvitation` | `924cd9e3-435d-5804-9d1e-c6aecadb9ab4` | `INVITATION_EVENT` | `READ` | Non-burnable | `0..3` (4) | `business-read-worker` | `CURRENT_INVITATION_CHANGED` |
| 7 | `MaterializeCurrentBinding` | `7b8d2838-bf7a-5117-868a-e5c14b4c0221` | `IDENTITY_EVENT` | `READ` | Non-burnable | `0..3` (4) | `business-read-worker` | `CURRENT_BINDING_CHANGED` |
| 8 | `MaterializeCommandResult` | `63664303-d33e-5d42-8cce-c4dae8023311` | `COMMAND_COMPLETION_EVENT` | `READ` | Non-burnable | `0..3` (4) | `results-worker` | `COMMAND_RESULT_AVAILABLE` |
| 9 | `MaterializeIdentityRequestResult` | `1d8ff246-358f-5ddd-a7f5-416efcfd7445` | `IDENTITY_COMPLETION_EVENT` | `READ` | Non-burnable | `0..3` (4) | `results-worker` | `IDENTITY_REQUEST_RESULT_AVAILABLE` |

**Total** : **9 consommations** (2 WRITE burnables + 7 READ non-burnables), **36 partitions logiques** (8 en `v3_write.partition_ownership` + 28 en `v3_read.partition_ownership`).

### 3.2 Les 5 runtimes JVM, leurs limites de concurrence et leurs pools HikariCP

Configurés dans [`docker/v3-five-runtimes/*.properties`](../../../docker/v3-five-runtimes/) et validés par [`ProcessSettings.java`](../../../app/compose-polling-burnable-write-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/process/ProcessSettings.java), [`ProcessPoolSettings.java`](../../../app/compose-polling-burnable-write-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/process/ProcessPoolSettings.java), [`MaterializeSettings.java`](../../../app/compose-polling-nonburnable-materialize-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/materialize/MaterializeSettings.java) et [`MaterializePoolSettings.java`](../../../app/compose-polling-nonburnable-materialize-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/materialize/MaterializePoolSettings.java) :

| Runtime | Module Maven | Profil Spring | `M_W` (`worker-limit`) | `M_C` (`consumption-limits`) | Partitions | Pools HikariCP (`minIdle = maxPoolSize`) | Polling & Lifecycle |
|---|---|---|---:|---|---:|---|---|
| `http-write` | `runtime-web-write` | `v3-http-admission` | N/A | `admission.capacity = 4` (Tomcat `maxThreads=4`) | 0 | `v3-http-admission = 4` (`pocoma_admission` sur `v3_write`) | Synchrone HTTP `202` |
| `http-read` | `runtime-web-read` | `v3-http-read` | N/A | `http-permits = 8`, `sql-permits = 8` (Tomcat `maxThreads=4`) | 0 | `http-read = 8` (`pocoma_read_http`), `maintenance = 1` (`pocoma_read_status`) | Synchrone HTTP `200/404/503` |
| `command-worker` | `runtime-worker-process` | `v3-consumption-worker-dev` | **`2`** | `Command=2`, `IdentityRequest=2` ($\sum M_C = 4 > M_W = 2$) | 8 WRITE (`2 × 4`) | `write-business = 4` (`M_W+2`), `write-control = 8` (`M_W+6`) | `min=2ms`, `max=20ms`, `err=50ms`, `quantum=4`, `batch=32`, `lease=30s`, `claim=60s`, `renew=10s` |
| `results-worker` | `runtime-worker-materialize` | `v3-read-worker` | **`2`** | `MaterializeCommandResult=2`, `MaterializeIdentityRequestResult=2` ($\sum M_C = 4 > M_W = 2$) | 8 READ (`2 × 4`) | `write-source-read = 4` (`M_W+2`, `readOnly`), `read-business = 4` (`M_W+2`), `read-control = 8` (`M_W+6`) | `min=2ms`, `max=20ms`, `err=50ms`, `quantum=4`, `batch=32`, `lease=30s`, `claim=60s`, `renew=10s` |
| `business-read-worker` | `runtime-worker-materialize` | `v3-read-worker` | **`3`** | 5 participants à `2` chacun ($\sum M_C = 10 > M_W = 3$) | 20 READ (`5 × 4`) | `write-source-read = 5` (`M_W+2`, `readOnly`), `read-business = 5` (`M_W+2`), `read-control = 9` (`M_W+6`) | `min=2ms`, `max=20ms`, `err=50ms`, `quantum=4`, `batch=32`, `lease=30s`, `claim=60s`, `renew=10s` |

> [!WARNING]
> **Deux faits structurels vérifiés dans le code à retenir impérativement pour C4** :
> 1. **`M_C` est un plafond, jamais une réservation garantie** ([`WorkerCapacity.java:56-62`](../../../app/orchestrator-consumption/src/main/java/com/kartaguez/pocoma/orchestrator/consumption/WorkerCapacity.java#L56-L62)) : `reserve()` alloue `min(wanted, min(workerLimit - total, limits.get(id) - used.get(id)))`. Dans `business-read-worker` (`M_W = 3`), la somme des plafonds individuels vaut `10`. Deux consommations (p. ex. `PotVersion` avec 2 tickets et `Invitation` avec 1 ticket) suffisent à saturer `total == M_W == 3`, laissant `0` ticket disponible pour `PotBalance` ou `CurrentBinding`.
> 2. **Validation statique stricte des noms de workers et tailles de pools** :
>    - [`ProcessSettings.java:260-277`](../../../app/compose-polling-burnable-write-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/process/ProcessSettings.java#L260-L277) impose que `command-worker` active *simultanément* `Command` et `IdentityRequest`.
>    - [`MaterializeSettings.java:331-363`](../../../app/compose-polling-nonburnable-materialize-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/materialize/MaterializeSettings.java#L331-L363) impose que `results-worker` active *exclusivement et simultanément* les 2 projections de résultats, et que `business-read-worker` n'active aucune projection de résultat (mais un `worker-name` personnalisé respectant `[a-z][a-z0-9-]{1,31}` peut activer tout sous-ensemble des 7 participants READ).
>    - [`ProcessPoolSettings.java:95-103`](../../../app/compose-polling-burnable-write-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/process/ProcessPoolSettings.java#L95-L103) et [`MaterializePoolSettings.java:106-113`](../../../app/compose-polling-nonburnable-materialize-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/materialize/MaterializePoolSettings.java#L106-L113) exigent une égalité stricte `maximum == minimumIdle == workerLimit + 2` (pools business / source-read) et `workerLimit + 6` (pools control). Toute modification de `M_W` en Phase 2 imposera d'ajuster les pools selon cette loi exacte (sauf décision explicite en Gate de faire évoluer cette garde).

### 3.3 Mécanismes internes d'ordonnancement, de Discovery, d'acquisition et de leases

1. **Threads par `ConsumptionWorker`** ([`ConsumptionWorker.java:232-238, 337-351`](../../../app/supra-polling-consumption/src/main/java/com/kartaguez/pocoma/supra/polling/consumption/ConsumptionWorker.java#L232-L238)) :
   - Un `ThreadPoolExecutor` fixe de taille `M_W` (`v3-attempt-0..M_W-1`) avec `SynchronousQueue` et `AbortPolicy`, pré-démarré au lancement.
   - `1` thread `v3-discovery-0` exécutant `DiscoveryLoop.runOnePass`.
   - `1` thread `v3-consumption-0` exécutant `ConsumptionLoop.runOnePass`.
   - `1` thread `v3-maintenance-0` exécutant `maintain()` (appels périodiques `settleOnce()` et `checkDeadlines()` toutes les `1 s`).
   - `3` threads `RenewalService` par autorité présente (`v3-renew-<AUTH>-0` et `-1` pour le renouvellement unitaire des claims en cours, `v3-renew-<AUTH>-2` pour le renouvellement groupé `renewPartitionBatch` des leases de partitions).
2. **Cadence de boucle (`LoopSchedule`)** ([`ConsumptionWorker.java:705-745`](../../../app/supra-polling-consumption/src/main/java/com/kartaguez/pocoma/supra/polling/consumption/ConsumptionWorker.java#L705-L745)) :
   - `tour = bindings.size() * max(partitions.size())`.
   - Pour `command-worker` et `results-worker` : `tour = 2 × 4 = 8` couples `(C, p)` ; `stepPoll = minPoll / 8 = 2 ms / 8 = 250 µs`.
   - Pour `business-read-worker` : `tour = 5 × 4 = 20` couples `(C, p)` ; `stepPoll = minPoll / 20 = 2 ms / 20 = 100 µs`.
   - Tant qu'aucun couple du tour ne progresse, chaque pas attend `stepPoll` (`100 µs` ou `250 µs`), puis à la fin du tour complet attend `backoff` qui double de `minPoll` (`2 ms`) jusqu'à `maxPoll` (`20 ms`).
   - **Effet d'interférence critique** : Dès qu'**une seule** consommation progresse sur un seul couple du tour (`progressed == true`), `backoff` est réinitialisé à `minPoll` (`2 ms`) et `tourProgressed` passe à `true` pour **l'ensemble du tour** de toutes les consommations co-hébergées. Ainsi, une activité continue sur `PotVersion` à `1 cmd/s` maintient `Invitation` et `CurrentBinding` en polling permanent à cadence maximale (`stepPoll = 100 µs`, sans backoff à `20 ms`).
3. **Round-robin `C` puis `p` et `pairLock`** ([`ConsumptionLoop.java:44-139`](../../../app/supra-polling-consumption/src/main/java/com/kartaguez/pocoma/supra/polling/consumption/ConsumptionLoop.java#L44-L139), [`DiscoveryLoop.java:34-99`](../../../app/supra-polling-consumption/src/main/java/com/kartaguez/pocoma/supra/polling/consumption/DiscoveryLoop.java#L34-L99)) :
   - À chaque passe, la boucle sélectionne la consommation suivante `selected`, avance `nextConsumption`, puis sélectionne la partition suivante `p` de cette consommation.
   - `DiscoveryLoop` et `ConsumptionLoop` se synchronisent localement sur `worker.pairLock(new Pair(binding, p))`. Une Discovery lente sur `(C, p)` bloque l'acquisition sur le même couple `(C, p)`, et toute opération JDBC lente dans `runOnePass()` bloque le thread unique de la boucle (`v3-discovery-0` ou `v3-consumption-0`) pour **toutes** les autres consommations du worker.
4. **Paramètres de Discovery et SoftCap** ([`ProcessComposeConfiguration.java:144-150`](../../../app/compose-polling-burnable-write-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/process/ProcessComposeConfiguration.java#L144-L150), [`MaterializeComposeConfiguration.java:53`](../../../app/compose-polling-nonburnable-materialize-postgres/src/main/java/com/kartaguez/pocoma/compose/polling/materialize/MaterializeComposeConfiguration.java#L53)) :
   - `windowSize = 128`, `insertBatchSize = 32`, `selectBatchSize = 32`, `softCap = 64` slots vivants (hors `QUARANTINED`) **par couple `(consumptionId, partitionId)`** (soit `256` slots vivants max sur les 4 partitions d'une consommation).

### 3.4 Trois profils de quotas CPU / Mémoire dans le dépôt

L'inspection du dépôt révèle trois profils distincts de quotas conteneurs qu'il est indispensable de distinguer :

| Service | `docs/architecture/runtimes.md` & `scripts/local/runtime.py` (`pocoma-local`) | `scripts/performance/l7-4a-profiles.json` (`run-l7-4a-baseline.py`, banc C1/C2/C3) | `scripts/performance/run-l7-spec09-e1.py` (banc E1 `small`) |
|---|---|---|---|
| `postgres` | `1.5` CPU / `1024m` RAM (volume persistant) | `2.0` CPU / `2048m` RAM (`tmpfs 1536m`) | `1.0` CPU / `640m` RAM (`tmpfs 512m`) |
| `http-write` | `1.0` CPU / `640m` RAM (`-Xmx384m`) | `0.5` CPU / `512m` RAM (`-Xmx512m`) | `0.5` CPU / `448m` RAM (`-Xmx256m`) |
| `http-read` | `1.0` CPU / `640m` RAM (`-Xmx384m`) | `0.5` CPU / `768m` RAM (`-Xmx512m`) | `0.5` CPU / `448m` RAM (`-Xmx256m`) |
| `command-worker` | `1.0` CPU / `640m` RAM (`-Xmx384m`) | `0.5` CPU / `512m` RAM (`-Xmx512m`) | `0.5` CPU / `448m` RAM (`-Xmx256m`) |
| `results-worker` | `1.0` CPU / `640m` RAM (`-Xmx384m`) | `0.5` CPU / `512m` RAM (`-Xmx512m`) | `0.5` CPU / `448m` RAM (`-Xmx256m`) |
| `business-read-worker` | `1.0` CPU / `640m` RAM (`-Xmx384m`) | `2.0` CPU / `1024m` RAM (`-Xmx512m`) | `0.5` CPU / `448m` RAM (`-Xmx256m`) |
| **Sous-total (PG + 5 JVM)** | **`6.5` CPU / `4 224 MiB`** *(+ Keycloak `1.5`/896m, Prom `0.5`/256m, Graf `0.5`/256m = `9.0` CPU)* | **`6.0` CPU / `5 376 MiB`** *(+ `spec09-test-issuer.mjs` sur hôte)* | **`3.5` CPU / `2 880 MiB`** |
| **Options JVM** | `-Xms64m -Xmx384m -XX:+UseSerialGC` | `-Xms64m -Xmx512m -XX:+UseSerialGC` | `-Xms64m -Xmx256m -XX:+UseSerialGC` |

---

## 4. Résumé de l'état de préparation pour la Phase 1

- **Maturité du plan et du protocole** : **COMPLET (`L7_C4_PLAN_READY_FOR_PHASE_1`)**.
- **Ancrage factuel** : Toutes les références au code, aux schémas SQL V1–V5, aux configurations `.properties` et aux bancs de performance ont été vérifiées sur le HEAD `181904cd02ffb7e3b9b60cf754cf365b99c2a3fa`.
- **Conformité aux arbitrages** : Les 13 arbitrages `C4-R1` à `C4-R13` sont intégrés et tracés individuellement dans [`traceability-matrix.md`](traceability-matrix.md).
- **Aucun changement hors mandat** : Aucun code Java, script Python, fichier SQL ou fichier `.properties` n'a été modifié. Aucune campagne ni exécution Maven n'a été lancée.
