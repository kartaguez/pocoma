# L7-C4 — Gabarit de Rapport de Décision pour la Gate `G-C4-1` (Phase 1 → Phase 2)

- **Statut du document** : **GABARIT NORMATIF (`TEMPLATE`)** — à instancier sous `docs/v3/L7_C4/phase1-diagnostic-report.md` à l'issue de la Phase 1.
- **Arbitrage couvert** : `C4-R13` (*« Le diagnostic précède toute optimisation. Une gate de décision humaine autorise explicitement, ou refuse, chaque famille d'optimisations envisagée. »*).

---

## Structure obligatoire du rapport de Gate `G-C4-1`

````markdown
# Pocoma V3 — L7-C4 — Rapport de Diagnostic (Phase 1) et Dossier de Décision Gate `G-C4-1`

- **Date de soumission** : YYYY-MM-DD
- **Branche & SHA testé** : `feat/v3-l7` @ `<SHA-40-chars>`
- **Statut de l'arbre de travail** : `<CLEAN / DIRTY (détail)>`
- **Modification de code de production / SQL / `.properties` en Phase 1** : **`AUCUNE (0)`**
- **Répertoire des preuves brutes** : `docs/testing/evidence/L7-C4/phase1/` (`manifest.sha256` : `<sha256>`)
- **Verdict technique de Phase 1** : `<L7_C4_PHASE_1_DIAGNOSTIC_COMPLETE>`

---

## 1. Provenance, environnement et validité expérimentale

### 1.1 Identité du code, des binaires et des images
| Élément | Valeur / Empreinte SHA-256 |
|---|---|
| Commit Git (`HEAD`) | `<SHA>` |
| `pocoma-runtime-web-write-0.0.1-SNAPSHOT-exec.jar` | `<sha256>` |
| `pocoma-runtime-web-read-0.0.1-SNAPSHOT-exec.jar` | `<sha256>` |
| `pocoma-runtime-worker-process-0.0.1-SNAPSHOT-exec.jar` | `<sha256>` |
| `pocoma-runtime-worker-materialize-0.0.1-SNAPSHOT-exec.jar` | `<sha256>` |
| Configurations `docker/v3-five-runtimes/*.properties` | `<sha256 par fichier>` |
| Image PostgreSQL 17 | `postgres@sha256:<digest>` (version serveur exacte : `<17.x>`) |
| Image JRE 21 | `eclipse-temurin@sha256:<digest>` |

### 1.2 Laboratoire local, budget matériel et contrôle de la charge parasite
- **Hôte physique** : `<Modèle Mac, CPU cores, RAM totale, OS, version Docker Desktop>`
- **Périmètre des ressources (limites allouées vs consommation réelle vs connexions JDBC)** :
  - Conteneurs (`http-write`, `http-read`, `command-worker`, `results-worker`, `business-read-worker`, `postgres`) + services auxiliaires (`test-issuer`, collecteur).
  - Tableau comparatif limites CPU / CPU réel (`cgroup usage_usec`, `throttled_usec`) / `mem_limit` / `RSS` & Heap / `maxPoolSize` JDBC.
- **Contrôle pré-vol de charge parasite** : `loadavg` avant chaque run `<val>`, `0` processus Maven/Java concurrent, `offerLagMs p95 <= 50 ms` vérifié sur tous les runs retenus.
- **Calibration de l'observateur (`L74-MET-02`)** : écart mesuré avec vs sans sonde `<delta ms / %>`.

---

## 2. Synthèse des mesures de bout en bout (Niveau 1 — `C4-R2`)

*(Rapporter pour chaque campagne la médiane et l'étendue `[min, max]` sur les $R$ répétitions, sans additionner les percentiles d'étapes.)*

| Campagne / Scénario | $R \times n$ | Débit offert / admis / terminalisé (cmd/s) | Admission HTTP `202` p50 / p95 (ms) | `CommandResult` E2E p50 / p95 (ms) | Jalon `C-03` E2E p50 / p95 / p99 (ms) | `PotBalance(P,V)` E2E p50 / p95 (ms) | `Registration` Binding E2E p50 / p95 (ms) | Backlog max (`H-K` / `slots`) & Âge max (ms) | Repère `SPEC-09` (informatif local) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| `C1-IDLE` (`0/s` + réveil) | `3 × 10` | `0.0` | — | — | — | — | — | `0 / 0` | Repos + réveil |
| `C1-MED-1` (`1/s` Moyen) | `3 × 120` | `...` | `...` | `...` | `...` | `...` | — | `...` | `p95 <= 500 ms` |
| `C1-LARGE-1` (`1/s` Grand) | `3 × 60` | `...` | `...` | `...` | `...` | `...` | — | `...` | `p95 <= 2 000 ms` |
| `C1-ID-1` (`1/s` Registration) | `3 × 60` | `...` | `...` | — | — | — | `...` | `...` | `p95 <= 500 ms` |
| `C1-DELETE-1` (`DeletePot`) | `3 × 20` | `...` | `...` | `...` | `...` *(interne D)* | `N/A (0 à D)` | — | `...` | `deleted=true` + `0` bal(D) |
| `C2-MED-10` (`10/s` soutenu) | `3 × 600` | `...` | `...` | `...` | `...` | `...` | — | `...` | `p95 <= 1 000 ms`, stationnaire |
| `C2-RAMP` (`1..50/s`) | `3 × 7 paliers` | $\lambda_{\max} = \dots$ | `...` | `...` | `...` | `...` | — | `...` | Seuil de saturation |
| `C2-MED-50` (`50/s` pointe) | `3 × 1500` | `...` | `...` | `...` | `...` | `...` | — | `...` | `p95 <= 3 000 ms` + résorption |
| `C2-CONTENTION-1POT` | `3 × 30s` | `...` | `...` | `...` | `...` | `...` | — | `...` | Conservation exacte CAS |
| `C3-INT-D5-VS-MED` | `3 × ...` | `...` | `...` | `...` | `...` *(Moyen)* | `...` *(D5 vs Med)* | `...` | `...` | Dégradation sous tâche longue |
| `C3-INT-POT-VS-ID` | `3 × ...` | `...` | `...` | `...` | `...` | `...` | `...` *(sous 20/s)* | `...` | Protection SLO Identity |
| `C3-INT-INV-VS-POT` | `3 × ...` | `...` | `...` | `...` | `...` | `...` | — | `...` | Compétition `M_W = 3` |
| `C4-BACKLOG-10K` (`+ 1/s`) | `3 × 10000` | Résorption : `... s` | `...` | `...` | `...` *(nouvelles 1/s)* | `...` | `...` *(nouvelles)* | `10000 -> 0` | Résorption `<= 300 s` |
| `C4-BACKLOG-READ-ONLY` | `3 × 1000` | Résorption : `... s` | `...` | `...` | `...` | `...` | — | `1000 -> 0` | Rattrapage `N-m` |

---

## 3. Réexamen de la réserve de performance C3 (§5.4 — Campagne `C4-P1-C5`)

### 3.1 Débit mixte sous concurrence (`C5-C3-CONCURRENCY`, $R = 5$, code C3 final CTE)
| Profil & Concurrence | Débit V4 / OFF (final./s) `[min..max]` | Débit V5 / ON (final./s) `[min..max]` | Écart médian `(ON - OFF) / OFF` | `execute` p50 / p95 OFF → ON (ms) | Temps SQL compteur C3 (ms/update) | Échantillons d'attente `COUNTER` / `WalSync` | Deadlocks / Rollbacks |
|---|---|---|---|---|---:|---|---:|
| `1 worker` — `COMMAND_RESULT` | `...` | `...` | `... %` | `... → ...` | `...` | `...` | `0 / 0` |
| `1 worker` — `IDENTITY_REQUEST_RESULT` | `...` | `...` | `... %` | `... → ...` | `...` | `...` | `0 / 0` |
| `1 worker` — `POT_VERSION` (+ `CURRENT_POT`) | `...` | `...` | `... %` | `... → ...` | `...` | `...` | `0 / 0` |
| `1 worker` — `POT_BALANCE` | `...` | `...` | `... %` | `... → ...` | `...` | `...` | `0 / 0` |
| `4 workers` — `COMMAND_RESULT` | `...` | `...` | `... %` | `... → ...` | `...` | `...` | `0 / 0` |
| `4 workers` — `IDENTITY_REQUEST_RESULT` | `...` | `...` | `... %` | `... → ...` | `...` | `...` | `0 / 0` |
| `4 workers` — `POT_VERSION` (+ `CURRENT_POT`) | `...` | `...` | `... %` | `... → ...` | `...` | `...` | `0 / 0` |
| `4 workers` — `POT_BALANCE` | `...` | `...` | `... %` | `... → ...` | `...` | `...` | `0 / 0` |
| **`4 workers` — `MIXED` (3 familles, 400 pub.)** | `...` | `...` | **`... %`** *(vs `-15.7 %` hist.)* | `... → ...` | `...` | `...` | `0 / 0` |
| **`4 workers` — `MIXED_FULL_5` (+ Visibility)** | `...` | `...` | **`... %`** | `... → ...` | `...` | `...` | `0 / 0` |

### 3.2 Coût WAL `PotBalance` XLARGE (`C5-C3-XLARGE-WAL`, $R = 5$, `500` shareholders, `pg_walinspect`)
| Régime (`500` shareholders) | WAL C2 / V4 (B/version) | WAL C3 / V5 final (B/version) | Delta net C3 (B/version) | Décomposition `pg_walinspect` (`Counter UPDATE` / `Event Heap INSERT` / `3 B-tree Indexes` / `FPI`) | Latence écriture p50 / p95 V4 → V5 (ms) |
|---|---:|---:|---:|---|---:|
| **Situation D** (`100` versions financières) | `...` | `...` | `+... B` (`+... %`) | `... / ... / ... / ...` | `... → ...` |
| **Situation A** (`50` versions à contenu réutilisé) | `...` | `...` | `+... B` (`+... %`) | `... / ... / ... / ...` | `... → ...` |

- **Conclusion sur les réserves `RES-C4-01` et `RES-C4-02`** : `<Énoncé factuel : part résorbée par la CTE finale, part irréductible liée aux index d'intégrité C3, impact réel ou négligeable sur les flux E2E>`.

---

## 4. Analyse des facteurs limitants internes (Niveau 2 — `C4-R2`)

### 4.1 Décomposition par consommation (9 consommations)
| Consommation | Runtime actuel | Rotations Discovery / Consumption (%) | `opportunity_wait` p50 / p95 (ms) | `prepare` p50 / p95 (ms) | `finalize` p50 / p95 (ms) | Saturations `worker` / `consumption` / `softcap` | Part du CPU utile dans le runtime (%) |
|---|---|---:|---:|---:|---:|---|---:|
| `Command` | `command-worker` | `...` | `...` | `...` | `...` | `... / ... / ...` | `... %` |
| `IdentityRequest` | `command-worker` | `...` | `...` | `...` | `...` | `... / ... / ...` | `... %` |
| `MaterializeCommandResult` | `results-worker` | `...` | `...` | `...` | `...` | `... / ... / ...` | `... %` |
| `MaterializeIdentityRequestResult` | `results-worker` | `...` | `...` | `...` | `...` | `... / ... / ...` | `... %` |
| `MaterializePotVersion` | `business-read-worker` | `...` | `...` | `...` | `...` | `... / ... / ...` | `... %` |
| `MaterializePotBalance` | `business-read-worker` | `...` | `...` | `...` | `...` | `... / ... / ...` | `... %` |
| `MaterializePotVisibility` | `business-read-worker` | `...` | `...` | `...` | `...` | `... / ... / ...` | `... %` |
| `MaterializeCurrentBinding` | `business-read-worker` | `...` | `...` | `...` | `...` | `... / ... / ...` | `... %` |
| `MaterializeInvitation` | `business-read-worker` | `...` | `...` | `...` | `...` | `... / ... / ...` | `... %` |

### 4.2 Attribution causale entre les 4 axes (PostgreSQL / Partitions / Ordonnanceur / Topologie)
1. **Axe PostgreSQL** (CPU, TPS contrôle vs métier, verrous `read_availability_counter` / `v3_event_counter`, WAL, autovacuum/HOT) : `<Constat chiffré>`.
2. **Axe Partitions logiques (`36` partitions `sha256-v1`)** (équilibrage `0..3`, contention par Pot vs par partition) : `<Constat chiffré>`.
3. **Axe Ordonnanceur local (`ConsumptionWorker`, `LoopSchedule`, `pairLock`, `WorkerCapacity`)** : `<Constat chiffré>`.
4. **Axe Topologie et dimensionnement des runtimes** (CPU/throttling cgroup, mémoire/GC, pools HikariCP `pending`) : `<Constat chiffré>`.

### 4.3 Verdict sur les hypothèses initiales (`H-C4-01` à `H-C4-07`)
| ID | Hypothèse | Verdict Phase 1 (`CONFIRMÉE` / `RÉFUTÉE` / `NUANCÉE`) | Preuve décisive |
|---|---|---|---|
| `H-C4-01` | Saturation de `M_W = 3` dans `business-read-worker` sous tâches longues `PotBalance` | `<Verdict>` | `<Preuve>` |
| `H-C4-02` | Interférence `Command` → `IdentityRequest` dans `command-worker` (`M_W = 2`) | `<Verdict>` | `<Preuve>` |
| `H-C4-03` | Surcoût CPU/SQL du backoff couplé dans `LoopSchedule` (`~40 %` Discovery à vide) | `<Verdict>` | `<Preuve>` |
| `H-C4-04` | Atténuation de l'écart de débit mixte C3 grâce au suffixe CTE unique | `<Verdict>` | `<Preuve>` |
| `H-C4-05` | Rendement limité de la réplication horizontale face au compteur unique C3 | `<Verdict>` | `<Preuve>` |
| `H-C4-06` | Incompressibilité structurelle des `~555 B/version` de WAL C3 en Situation A | `<Verdict>` | `<Preuve>` |
| `H-C4-07` | Effet de barrière FIFO de la séquence dense `COMMAND` sous backlog `10 000` | `<Verdict>` | `<Preuve>` |

### 4.4 Incertitudes restantes et limites du laboratoire local
- `<Liste explicite des incertitudes non résolues par le banc mono-hôte>`.

---

## 5. Optimisations candidates pour la Phase 2, alternatives écartées et recommandation

### 5.1 Optimisations candidates soumises à décision
| ID Lot | Intitulé de l'intervention | Goulot Phase 1 ciblé | Modification exacte envisagée | Bénéfice attendu | Risques & Invariants à vérifier | Tranche Maven (`Reactor_Verification_Policy.md`) | Recommandation (`RETENIR` / `ÉCARTER`) |
|---|---|---|---|---|---|---|---|
| **P2-O1a** | Concurrence `M_W` / `M_C` & Pools HikariCP (3 workers) | `<Goulot>` | `<Détail .properties>` | `<Gain attendu>` | Loi `M_W+2`/`M_W+6`, bornes `verifyTimingBounds`, `< 100` conn. PG | `NONE` (ou `PROJECTION,COMMAND` si test config) | `<...>` |
| **P2-O1b** | Cadence de polling (`min-poll`, `max-poll`, `quantum`) | `<Goulot>` | `<Détail .properties>` | `<Gain attendu>` | Compromis TPS contrôle vs latence E2E `C-03` à `1 cmd/s` | `NONE` | `<...>` |
| **P2-O2** | Regroupement alternatif des consommations (`T1`, `T2` ou `T3`) | `<Goulot>` | `<Topologie choisie>` | `<Gain attendu>` | Budget RAM/CPU constant, 36 leases couverts, isolation des rôles | `NONE` (ou `PROJECTION` si garde `MaterializeSettings`) | `<...>` |
| **P2-O3** | Réallocation CPU / RAM / GC entre conteneurs à budget constant | `<Goulot>` | `<Quotas conteneurs & JVM options>` | `<Gain attendu>` | Absence d'OOM et de throttling cgroup | `NONE` | `<...>` |
| **P2-O4** | Comparaison Vertical (`1 × M_W=4`) vs Horizontal (`2 × M_W=2`) | `<Goulot>` | `<Partage 0\|1 et 2\|3 vs 0\|1\|2\|3>` | `<Gain attendu>` | Contention compteur C3, connexions Hikari doublées | `NONE` | `<...>` |
| **P2-O5** | Optimisation SQL ciblée (fusion `requireCompletionReady` + preuve C3) | `<Goulot>` | `<Fichiers infra-postgres-read>` | `<Gain attendu>` | Oracles de recovery C3 (`SATISFIED`/`MISSING`/`DIVERGENT`) | `PROJECTION` (+ `WEB`, `runtime-monolith`) | `<...>` |
| **P2-O6** | Backoff indépendant par `Binding` dans `LoopSchedule` | `<Goulot>` | `<ConsumptionWorker.java>` | `<Gain attendu>` | Absence de famine et réveil immédiat sur signal | `PROJECTION,COMMAND` | `<...>` |
| **P2-O7** | Recherche finale du dimensionnement sobre | Sobriété (`C4-R6`) | `<Paliers de réduction CPU/RAM>` | `<Économie ressources>` | Marge `>= 20 %` sur p95 et heap | `NONE` | `<...>` |

### 5.2 Alternatives explicitement écartées à l'issue de la Phase 1
| Alternative écartée | Raison factuelle de l'abandon (preuve Phase 1 ou invariant §8) |
|---|---|
| `<Ex: Pilotage dynamique de partitions / work-stealing>` | Interdit par `C4-R4`, `C4-R9` et §8 ; inutile au vu de la distribution `sha256-v1`. |
| `<Ex: Partitionnement des compteurs C3 par Pot>` | Romprait le contrat C3-R1 de curseur global par famille ratifié en C3. |
| `<Autre piste réfutée par les mesures>` | `<Preuve chiffrée issue de C4-P1-C1..C5>`. |

### 5.3 Ordre d'exécution priorisé recommandé pour la Phase 2
1. `<Étape 1 recommandée à variable isolée>`
2. `<Étape 2 recommandée à variable isolée>`
3. `<Étape 3 : Dimensionnement sobre P2-O7>`

---

## 6. Formulaire d'arbitrage humain — Gate `G-C4-1`

> **Instructions pour la décision humaine (`C4-R13`)** : Cocher ou renseigner explicitement la décision (`AUTORISÉ` / `REFUSÉ` / `DIFFÉRÉ`) pour chaque famille d'interventions avant tout lancement de la Phase 2.

| Famille d'optimisations | Décision humaine (`AUTORISÉ` / `REFUSÉ` / `DIFFÉRÉ`) | Conditions, bornes ou réserves imposées par le décideur |
|---|---|---|
| **P2-O1a** — Ajustement `worker-limit` (`M_W`), `consumption-limits` (`M_C`) et pools HikariCP | `[ ] AUTORISÉ  [ ] REFUSÉ  [ ] DIFFÉRÉ` | |
| **P2-O1b** — Ajustement de la cadence de polling (`min-poll`, `max-poll`, `quantum`) | `[ ] AUTORISÉ  [ ] REFUSÉ  [ ] DIFFÉRÉ` | |
| **P2-O2** — Regroupements alternatifs de consommations en runtimes (`T1` / `T2` / `T3`) | `[ ] AUTORISÉ  [ ] REFUSÉ  [ ] DIFFÉRÉ` | Topologie(s) autorisée(s) : `...` |
| **P2-O3** — Réallocation des quotas CPU / RAM / GC à budget global constant | `[ ] AUTORISÉ  [ ] REFUSÉ  [ ] DIFFÉRÉ` | |
| **P2-O4** — Comparaison dimensionnement vertical vs réplication horizontale | `[ ] AUTORISÉ  [ ] REFUSÉ  [ ] DIFFÉRÉ` | |
| **P2-O5** — Réduction des contentions SQL (`infra-postgres-read`) | `[ ] AUTORISÉ  [ ] REFUSÉ  [ ] DIFFÉRÉ` | |
| **P2-O6** — Amélioration ciblée de l'ordonnancement (`LoopSchedule` backoff par `Binding`) | `[ ] AUTORISÉ  [ ] REFUSÉ  [ ] DIFFÉRÉ` | |
| **P2-O7** — Recherche du dimensionnement sobre final | `[ ] AUTORISÉ  [ ] REFUSÉ  [ ] DIFFÉRÉ` | |

- **Verdict global de la Gate `G-C4-1`** :
  - `[ ] GO_L7_C4_PHASE_2_SELECTIVE_OPTIMIZATION` *(selon la sélection ci-dessus)*
  - `[ ] CLOSE_L7_C4_AFTER_PHASE_1_DIAGNOSTIC` *(aucune optimisation requise ; les performances et la topologie actuelles sont acceptées en l'état)*
- **Date et signataire** : `...`
````
