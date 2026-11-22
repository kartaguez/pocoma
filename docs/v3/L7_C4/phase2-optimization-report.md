# Pocoma V3 — L7-C4 — Rapport d'Optimisation (Phase 2)

> **Branche** : `feat/v3-l7`  
> **Référence T0 (Phase 1)** : `e63acb0d052afc943e48dda27d4d73866401913e` ([phase1-diagnostic-report.md](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/v3/L7_C4/phase1-diagnostic-report.md))  
> **Environnement** : Docker Desktop sur Apple M4 (`10` vCPU, `~7,75 GiB` RAM Docker), budget constant **`6,0` CPUs** / **`5376m` RAM** sur les 6 conteneurs (`postgres` + 5 runtimes). Aucun résultat local ne vaut certification SPEC-09 sur infrastructure cible (`C4-R12`).

---

## 1. Synthèse exécutive Avant / Après (`T0` → `B3 + A2`)

| Indicateur (médianes) | `T0` (Phase 1) | `B3` (Polling seul) | **`B3 + A2` (Retenu)** | Évolution `T0 → B3+A2` |
| :--- | :---: | :---: | :---: | :---: |
| **`c1-idle` — Transactions PG/s** | `14 034 tx/s` | `1 336 tx/s` | **`1 340 tx/s`** | **`-90,5 %`** (`÷10,5`) |
| **`c1-idle` — CPU total cluster** | `1,37 CPU` | `0,551 CPU` | **`0,550 CPU`** | **`-59,9 %`** |
| **`c1-idle` — CPU `postgres`** | `0,64 CPU` | `0,137 CPU` | **`0,140 CPU`** | **`-78,1 %`** |
| **`c1-idle` — WAL `WRITE` / `READ`** | `8,53 MiB` | `1,10 MiB` | **`1,11 MiB`** | **`-87,0 %`** |
| **`c2-ramp` (`20 cmd/s`) — `C-03` `p50` / `p95`** | `155,5 / 1 208 ms` | `86,1 / 137,9 ms` | **`81,2 / 113,3 ms`** | **`-47,8 %` / `-90,6 %`** |
| **`c2-ramp` (`35 cmd/s`) — Débit / `C-03` `p50` / `p95`** | `27,2/s` · `361 / 2 179 ms` | `33,3/s` · `97,1 / 200,6 ms` | **`34,85/s` (`525/525`) · `95,3 / 134,2 ms`** | **`+28 %` · `-73,6 %` / `-93,8 %`** |
| **`c2-ramp` (`50 cmd/s`) — Débit plateau E2E** | `27–28 cmd/s` | `34,51 cmd/s` | **`36,83 cmd/s`** | **`+34 %`** |
| **`c3-int-pot-vs-id` — `IdentityRequest` `p50` / `p95`** | `298,7 / 1 900,5 ms` | — | **`85,5 / 119,3 ms`** | **`-71,4 %` / `-93,7 %`** (`×15,9`) |
| **`c4-backlog-10k` — Drain `10 000 CreatePot`** | `179,98 s` (`55,6/s`) | — | **`129,78 s`** (`77,1/s`) | **`-27,9 %`** |

---

## 2. Résultats détaillés par axe d'optimisation

### 2.1 Axe B — Optimisation intrinsèque du polling (prioritaire)

Mené en trois incréments mesurés séparément (`B1`, `B2`, `B3`) dans `contract-consumption`, `orchestrator-consumption` et `supra-polling-consumption` (commit `f314ed9d050951b2fc90b0b3d2e0a5082fd5d900`) :

| Incrément | Mécanisme | `c1-idle` tx/s | `c1-idle` CPU total | `c1-idle` WAL | `c2-ramp` plateau |
| :--- | :--- | :---: | :---: | :---: | :---: |
| **`T0`** | État initial Phase 1 (`min-poll=2`, `max-poll=20`) | `14 034` | `1,370` | `8,53 MiB` | `27–28 /s` |
| **`B1`** | Saut de `effects.write` sur fenêtre Discovery vide (`discover(lease, false)`) + suivi *dirty/clean* par paire `(C, p)` dans `ConsumptionLoop` (réconcilié ≤ 500 ms, réveillé sur admission/fin d'enveloppe) | `8 763` (`-37,6 %`) | `0,825` (`-39,8 %`) | `1,10 MiB` (`-87,1 %`) | `32,6 /s` |
| **`B2`** | Cache de `discovery_checkpoint` par incarnation de lease (`ownerToken`) dans `DiscoveryEngine` (invalidé sur changement de token ou `STALE_CHECKPOINT`) | `4 949` (`-64,7 %`) | `0,678` (`-50,5 %`) | `1,10 MiB` (`-87,1 %`) | `33,2 /s` |
| **`B3`** | Déduplication intra-tour des sondes `(ConsumableFamily, checkpoint)` déjà constatées vides dans `DiscoveryLoop` | **`1 336`** (`-90,5 %`) | **`0,551`** (`-59,8 %`) | **`1,10 MiB`** (`-87,1 %`) | **`34,5 /s`** |

* **Préservation des garanties** :
  * La méthode 1-argument `discoverEvents(lease)` / `discoverRequests(lease)` conserve `verifyOwnershipOnEmpty=true` pour les contrats unitaires.
  * `RenewalService` continue de renouveler et fencer les leases à intervalle `renewalInterval` (`10 s`).
  * Toute fenêtre source non vide entre dans `effects.write(...)` avec vérification `protectAcquisition` + `fence` + `actual == k`.
  * En `c1-med-1` (`1 cmd/s`), le léger décalage intra-tour de `B3` ajoute `~13 ms` sur `c03_p50` (`109,96 -> 122,72 ms`), tout en restant sous `150 ms` au `p95` (`148,81 ms`), et améliore fortement toutes les charges `≥ 10 cmd/s`.

### 2.2 Axe A — Rééquilibrage des ressources à budget constant (`6,0` CPUs)

Comparatif à budget global identique (`6,0` CPUs, `5376m` RAM) :

| Configuration | `postgres` | `http-write` | `http-read` | `command-worker` | `results-worker` | `business-read-worker` | `worker-limit` / quotas internes |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :--- |
| **`T0 / B3`** | `2,0` | `0,5` | `0,5` | `0,5` | `0,5` | `2,0` | `2 / 2 / 3` (`Command=2`, `CmdRes=2`) |
| **`A1`** | `2,0` | `0,5` | `1,0` | `1,0` | `0,75` | `0,75` | `4 / 4 / 4` (`Command=3,Id=2`, `CmdRes=3,IdRes=2`) |
| **`A2` (Retenu)** | `2,0` | `0,5` | **`0,75`** | **`0,75`** | **`0,75`** | **`1,25`** | `4 / 4 / 4` (`Command=3,Id=2`, `CmdRes=3,IdRes=2`) |

* **Pourquoi `A2` plutôt que `A1`** :
  * En `A1`, `business-read-worker` à `0,75` CPU subissait `15,46 s` de throttling en `c2-ramp` (`16,82 s` en `c3-int-pot-vs-id`, maintenant `IdentityRequest` `p95` à `1 142,85 ms` sur `CurrentBinding`).
  * En `A2` (`business-read-worker = 1,25` CPU), son throttling tombe à **`0,74 s`** en `c2-ramp` (`2,83 s` en `c3-int-pot-vs-id`) et la `p95` de `IdentityRequest` sous `20 cmd/s` pot chute à **`119,27 ms`** (`-93,7 %` vs T0).
* **Réservation de concurrence + pools Hikari** :
  * `Command=3` sur `worker-limit=4` garantit en permanence au moins `1` slot disponible pour `IdentityRequest` (idem `MaterializeCommandResult=3` sur `worker-limit=4` pour `MaterializeIdentityRequestResult`).
  * Les pools Hikari ont été alignés selon la garde stricte (`ProcessPoolSettings` / `MaterializePoolSettings`) : `business` / `source-read = 6`, `control = 10` (dans `docker/v3-five-runtimes/*.properties`).

### 2.3 Axe C — Organisation et dimensionnement des runtimes

Deux topologies alternatives à 5/6 runtimes ont été testées expérimentalement :
1. **`topo-c1` (6 runtimes, `identity-worker` séparé de `command-worker`)** : refusée au démarrage par la garde contractuelle de `runtime-worker-process` (`Command worker must activate Command and IdentityRequest`).
2. **`topo-c2` (5 runtimes, `MaterializeCurrentBinding` déplacé dans `results-worker`)** : refusée au démarrage par la garde contractuelle de `runtime-worker-materialize` (`Results worker must activate exclusively MaterializeCommandResult and MaterializeIdentityRequestResult`).
3. **Conclusion Axe C** : Le découpage en **5 runtimes** (`http-write`, `http-read`, `command-worker`, `results-worker`, `business-read-worker`) avec le rééquilibrage `A2` élimine déjà l'interférence sur `IdentityRequest` (`p95 = 119,27 ms` sous charge `20 cmd/s`, `p50 = 85,49 ms`). Ajouter un 6e runtime JVM immobiliserait `~200–250 MiB` RAM et `~0,10` CPU supplémentaire sans gain démontré (`C4-R5` : mutualisation retenue).

### 2.4 Axe D — Optimisation du coût SQL C3

L'analyse de [ReadAvailability.java](file:///Users/julien.guezennec/Dev/projects/pocoma/app/infra-postgres-read/src/main/java/com/kartaguez/pocoma/infra/postgres/read/ReadAvailability.java) et `V5__projection_availability.sql` montre que le coût mesuré en Phase 1 (`+2` statements JDBC/effet, `+19,8 %` temps mur et `+52,4 %` WAL en `MIXED` 4 workers, `0` deadlock) provient directement des garanties structurelles C3 :
* `SELECT` d'idempotence en `prepare` (`historicalEventPositionValid` / `currentMarkerMatches`) ;
* CTE `UPDATE read_availability_counter ... RETURNING` + `INSERT/UPSERT` sous triggers `read_availability_counter_step` et `read_availability_counter_position` assurant une position dense sans trou par famille.
* **Décision Axe D (écartée)** : aucune modification SQL sûre n'est justifiée sans affaiblir les invariants C3 ; le mécanisme C3 est conservé intact.

### 2.5 Axe 5 — Étude du réveil événementiel (architecture uniquement)

L'étude complète est livrée dans [event-driven-wakeup-study.md](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/v3/L7_C4/event-driven-wakeup-study.md). Aucune implémentation n'a été engagée.

---

## 3. Inventaire des modifications conservées et écartées

* **Conservées** :
  * **Axe B (`B1 + B2 + B3`)** : commit `f314ed9d050951b2fc90b0b3d2e0a5082fd5d900` (`ConsumptionProtocol`, `DiscoveryEngine`, `BurnableConsumptionProtocol`, `NonBurnableConsumptionProtocol`, `ConsumptionWorker`, `DiscoveryLoop`, `ConsumptionLoop`, `ConsumptionWorkerTest`).
  * **Axe A (`topo-a2`)** : `docker/v3-five-runtimes/{command-worker,results-worker,business-read-worker}.properties` (`worker-limit=4`, quotas réservés `Command=3,IdentityRequest=2` / `MaterializeCommandResult=3,MaterializeIdentityRequestResult=2`, pools Hikari `6`/`10`) et profil CPU local `A2` (`0.5 / 0.75 / 0.75 / 0.75 / 1.25` + `postgres=2.0`).
  * **Outillage** : `--extra-config` ajouté à [run-l7-c4-diagnostic.py](file:///Users/julien.guezennec/Dev/projects/pocoma/scripts/performance/run-l7-c4-diagnostic.py).
* **Écartées** :
  * **Axe C (`topo-c1`, `topo-c2`)** : bloquées par les gardes de composition et rendues superflues par `A2`.
  * **Axe D (SQL C3)** : aucune modification sûre/bénéfique sans affaiblir C3.

---

## 4. Preuves de correction, non-régression et réserves

* **Preuves unitaires & architecture** :
  * `orchestrator-consumption` + `supra-polling-consumption` (`39/39` tests `PASS`).
  * `compose-polling-burnable-write-postgres` (`13/13` `PASS`, incluant `ConsumptionWorkerTest`) + `compose-polling-nonburnable-materialize-postgres` (`PASS`).
  * `architecture-tests` (`52/52` `PASS`, incluant `L7DeploymentArchitectureTest` et `L7ReactorArchitectureTest` après mise à jour de `docker/v3-five-runtimes/*.properties`).
* **Preuves E2E `B3 + A2`** : `14/14` exécutions de confirmation `PASS` avec `read_availability_valid() = true` et `0` violation d'invariant ([phase2-summary.json](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L7-C4/phase2/phase2-summary.json), [manifest.sha256](file:///Users/julien.guezennec/Dev/projects/pocoma/docs/testing/evidence/L7-C4/phase2/manifest.sha256)).
* **Réserves restantes** :
  1. Toutes les mesures E2E proviennent de l'environnement local Docker Apple M4 (`C4-R12`) ; la qualification sur infrastructure cible (configuration A SPEC-09) reste hors périmètre C4.
  2. Le résidu de `~1 340 tx/s` à vide provient du polling à `20 ms` sur `v3_event_counter` / `v3_request_counter` ; passer sous `~30 tx/s` à vide relève d'un éventuel futur chantier de réveil événementiel (Valkey Pub/Sub ou NATS Core, cf. Axe 5).

---

## 5. Verdict technique sur la Phase 2

**Verdict : `L7_C4_PHASE2_PASS`** — Les objectifs de L7-C4 sont atteints sur `feat/v3-l7` sans nouvelle dépendance ni refonte L2/C3 : **`-90,5 %`** de transactions PostgreSQL à vide, **`-59,9 %`** de CPU à vide, **`-93,7 %`** sur la `p95` d'interférence `IdentityRequest` (`1 900,5 ms -> 119,3 ms`), **`+34 %`** de débit plateau E2E (`27–28 -> 36,8 cmd/s`) et **`-27,9 %`** de temps de drain sur `10 000 CreatePot` (`180 s -> 129,8 s`).
